/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.plugins.gitsync;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TLS Manager's two keystores -- its local key pairs and its trusted certificates --
 * as Git Sync reads and writes them, plus their PEM file form.
 *
 * <p>TLS Manager holds both keystores in memory (CertificateService) and persists them
 * through a backend: base64 PKCS#12 in the configuration table by default, or files.
 * Writing the table directly would leave the in-memory copies stale until a restart,
 * so every change goes through its public {@code storeExtraKeyStore} /
 * {@code storeExtraTrustStore}, which reload the live keystore and persist it in one
 * call. Reflection rather than a compile-time dependency: TLS Manager is a third-party
 * extension, this one must still load without it, and its classes are only on the
 * classpath when it is installed.
 *
 * <p><b>The private keys are written in plain text.</b> That is the operator's chosen
 * policy for this scope: anyone who can read the repository, or its history, can read
 * every key pair in it, a CA's included. The scope is opt-in for that reason.
 */
final class TlsManagerStore {

    static final String PLUGIN_CLASS =
        "org.openintegrationengine.tlsmanager.server.TLSServicePlugin";

    private static final Object LOCK = new Object();

    private static final Pattern ALIAS_LINE = Pattern.compile("(?m)^#\\s*alias:\\s*(.+?)\\s*$");
    private static final Pattern PEM_BLOCK = Pattern.compile(
        "-----BEGIN ([A-Z0-9 ]+)-----\\s*([A-Za-z0-9+/=\\s]+?)-----END \\1-----");

    /** A key pair: private key and certificate chain, leaf first. */
    static final class KeyPairEntry {
        final PrivateKey key;
        final X509Certificate[] chain;

        KeyPairEntry(PrivateKey key, X509Certificate[] chain) {
            this.key = key;
            this.chain = chain;
        }

        boolean sameAs(KeyPairEntry other) {
            if (other == null || !Arrays.equals(key.getEncoded(), other.key.getEncoded())
                    || chain.length != other.chain.length) {
                return false;
            }
            for (int i = 0; i < chain.length; i++) {
                if (!chain[i].equals(other.chain[i])) {
                    return false;
                }
            }
            return true;
        }
    }

    /** What an upsert changed. */
    static final class Upserted {
        int added;
        int updated;
        int unchanged;
    }

    private TlsManagerStore() {
    }

    // ------------------------------------------------------------------
    // TLS Manager, by reflection
    // ------------------------------------------------------------------

    /** True when TLS Manager's classes are present and it has started. */
    static boolean available() {
        try {
            service();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static Object service() throws Exception {
        Class<?> plugin;
        try {
            plugin = Class.forName(PLUGIN_CLASS, true, TlsManagerStore.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            try {
                plugin = Class.forName(PLUGIN_CLASS, true,
                    Thread.currentThread().getContextClassLoader());
            } catch (ClassNotFoundException again) {
                throw new IllegalStateException("TLS Manager is not installed on this engine");
            }
        }
        Object instance = plugin.getMethod("getPluginInstance").invoke(null);
        if (instance == null) {
            throw new IllegalStateException("TLS Manager has not started");
        }
        Object service = plugin.getMethod("getCertificateService").invoke(instance);
        if (service == null) {
            throw new IllegalStateException("TLS Manager's certificate service is not available");
        }
        return service;
    }

    /** "Key" for the local key pairs, "Trust" for the trusted certificates. */
    private static KeyStore live(Object service, String which) throws Exception {
        KeyStore ks = (KeyStore) service.getClass()
            .getMethod("getExternal" + which + "Store").invoke(service);
        if (ks == null) {
            throw new IllegalStateException("TLS Manager has no " + which.toLowerCase() + " store");
        }
        return ks;
    }

    private static char[] password(Object service, String which) throws Exception {
        Field field = service.getClass().getDeclaredField("extra" + which + "StoreBackend");
        field.setAccessible(true);
        Object backend = field.get(service);
        if (backend == null) {
            throw new IllegalStateException("TLS Manager's " + which.toLowerCase()
                + " store backend is not set up");
        }
        for (Class<?> iface : backend.getClass().getInterfaces()) {
            try {
                char[] pw = (char[]) iface.getMethod("loadPassword").invoke(backend);
                return pw == null ? new char[0] : pw;
            } catch (NoSuchMethodException ignored) {
                // try the next interface
            }
        }
        throw new IllegalStateException("TLS Manager's backend has no loadPassword");
    }

    /** Saves a whole keystore through TLS Manager, which reloads its live copy. */
    private static void store(Object service, String which, KeyStore updated, char[] pw)
            throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        updated.store(bytes, pw);
        try {
            service.getClass().getMethod("storeExtra" + which + "Store", byte[].class, char[].class)
                .invoke(service, bytes.toByteArray(), pw);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IllegalStateException("TLS Manager would not save its "
                + which.toLowerCase() + " store: " + cause.getMessage(), cause);
        }
    }

    /** A modifiable copy of a live keystore, so a failed change leaves TLS Manager as it was. */
    private static KeyStore copy(KeyStore live, char[] pw) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        live.store(bytes, pw);
        KeyStore copy = KeyStore.getInstance(live.getType());
        copy.load(new ByteArrayInputStream(bytes.toByteArray()), pw);
        return copy;
    }

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    static Map<String, KeyPairEntry> keyPairs() throws Exception {
        Object service = service();
        char[] pw = password(service, "Key");
        Map<String, KeyPairEntry> out = new LinkedHashMap<>();
        synchronized (LOCK) {
            KeyStore ks = live(service, "Key");
            List<String> aliases = Collections.list(ks.aliases());
            Collections.sort(aliases, String.CASE_INSENSITIVE_ORDER);
            for (String alias : aliases) {
                if (!ks.isKeyEntry(alias)) {
                    continue;
                }
                Key key = ks.getKey(alias, pw);
                Certificate[] certs = ks.getCertificateChain(alias);
                if (key instanceof PrivateKey && certs != null && certs.length > 0) {
                    out.put(alias, new KeyPairEntry((PrivateKey) key, x509(certs)));
                }
            }
        }
        return out;
    }

    static Map<String, X509Certificate> trusted() throws Exception {
        Object service = service();
        Map<String, X509Certificate> out = new LinkedHashMap<>();
        synchronized (LOCK) {
            KeyStore ks = live(service, "Trust");
            List<String> aliases = Collections.list(ks.aliases());
            Collections.sort(aliases, String.CASE_INSENSITIVE_ORDER);
            for (String alias : aliases) {
                Certificate cert = ks.getCertificate(alias);
                if (ks.isCertificateEntry(alias) && cert instanceof X509Certificate) {
                    out.put(alias, (X509Certificate) cert);
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Writing: upsert, and removal for the orphan DELETE action
    // ------------------------------------------------------------------

    static Upserted upsertKeyPairs(Map<String, KeyPairEntry> wanted) throws Exception {
        Upserted result = new Upserted();
        if (wanted.isEmpty()) {
            return result;
        }
        Object service = service();
        char[] pw = password(service, "Key");
        synchronized (LOCK) {
            KeyStore live = live(service, "Key");
            KeyStore copy = copy(live, pw);
            for (Map.Entry<String, KeyPairEntry> e : wanted.entrySet()) {
                String alias = e.getKey();
                KeyPairEntry current = null;
                if (copy.isKeyEntry(alias)) {
                    Key key = copy.getKey(alias, pw);
                    Certificate[] certs = copy.getCertificateChain(alias);
                    if (key instanceof PrivateKey && certs != null) {
                        current = new KeyPairEntry((PrivateKey) key, x509(certs));
                    }
                }
                if (e.getValue().sameAs(current)) {
                    result.unchanged++;
                    continue;
                }
                if (copy.containsAlias(alias)) {
                    result.updated++;
                } else {
                    result.added++;
                }
                copy.setKeyEntry(alias, e.getValue().key, pw, e.getValue().chain);
            }
            if (result.added + result.updated > 0) {
                store(service, "Key", copy, pw);
            }
        }
        return result;
    }

    static Upserted upsertTrusted(Map<String, X509Certificate> wanted) throws Exception {
        Upserted result = new Upserted();
        if (wanted.isEmpty()) {
            return result;
        }
        Object service = service();
        char[] pw = password(service, "Trust");
        synchronized (LOCK) {
            KeyStore copy = copy(live(service, "Trust"), pw);
            for (Map.Entry<String, X509Certificate> e : wanted.entrySet()) {
                Certificate current = copy.isCertificateEntry(e.getKey())
                    ? copy.getCertificate(e.getKey()) : null;
                if (e.getValue().equals(current)) {
                    result.unchanged++;
                    continue;
                }
                if (copy.containsAlias(e.getKey())) {
                    result.updated++;
                } else {
                    result.added++;
                }
                copy.setCertificateEntry(e.getKey(), e.getValue());
            }
            if (result.added + result.updated > 0) {
                store(service, "Trust", copy, pw);
            }
        }
        return result;
    }

    /** Removes key pairs and trusted certificates; returns how many went. */
    static int remove(Collection<String> keyPairAliases, Collection<String> trustedAliases)
            throws Exception {
        Object service = service();
        int removed = 0;
        synchronized (LOCK) {
            if (!keyPairAliases.isEmpty()) {
                char[] pw = password(service, "Key");
                KeyStore copy = copy(live(service, "Key"), pw);
                int before = copy.size();
                for (String alias : keyPairAliases) {
                    if (copy.containsAlias(alias)) {
                        copy.deleteEntry(alias);
                    }
                }
                if (copy.size() != before) {
                    store(service, "Key", copy, pw);
                    removed += before - copy.size();
                }
            }
            if (!trustedAliases.isEmpty()) {
                char[] pw = password(service, "Trust");
                KeyStore copy = copy(live(service, "Trust"), pw);
                int before = copy.size();
                for (String alias : trustedAliases) {
                    if (copy.containsAlias(alias)) {
                        copy.deleteEntry(alias);
                    }
                }
                if (copy.size() != before) {
                    store(service, "Trust", copy, pw);
                    removed += before - copy.size();
                }
            }
        }
        return removed;
    }

    // ------------------------------------------------------------------
    // PEM files
    // ------------------------------------------------------------------

    /**
     * A key pair file: a header naming the alias -- what identifies the entry, so the
     * file name is free to be a slug -- then the private key as unencrypted PKCS#8 and
     * the certificate chain, leaf first.
     */
    static String renderKeyPair(String alias, KeyPairEntry entry) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("# TLS Manager key pair, exported by the Git Sync plugin.\n");
        sb.append("# The private key below is NOT encrypted.\n");
        sb.append("# alias: ").append(alias).append('\n');
        sb.append("# subject: ").append(entry.chain[0].getSubjectX500Principal().getName()).append('\n');
        sb.append("# expires: ").append(entry.chain[0].getNotAfter().toInstant()).append("\n\n");
        sb.append(pem("PRIVATE KEY", entry.key.getEncoded()));
        for (X509Certificate cert : entry.chain) {
            sb.append(pem("CERTIFICATE", cert.getEncoded()));
        }
        return sb.toString();
    }

    static String renderTrusted(String alias, X509Certificate cert) throws Exception {
        return "# TLS Manager trusted certificate, exported by the Git Sync plugin.\n"
            + "# alias: " + alias + "\n"
            + "# subject: " + cert.getSubjectX500Principal().getName() + "\n"
            + "# expires: " + cert.getNotAfter().toInstant() + "\n\n"
            + pem("CERTIFICATE", cert.getEncoded());
    }

    /** The alias a file names, or null. */
    static String aliasOf(String text) {
        Matcher m = ALIAS_LINE.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    static KeyPairEntry parseKeyPair(String text) throws Exception {
        PrivateKey key = null;
        List<X509Certificate> chain = new ArrayList<>();
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        Matcher m = PEM_BLOCK.matcher(text);
        while (m.find()) {
            byte[] der = Base64.getMimeDecoder().decode(m.group(2));
            switch (m.group(1)) {
                case "PRIVATE KEY":
                    key = privateKey(der);
                    break;
                case "CERTIFICATE":
                    chain.add((X509Certificate) cf.generateCertificate(new ByteArrayInputStream(der)));
                    break;
                case "RSA PRIVATE KEY":
                case "EC PRIVATE KEY":
                case "ENCRYPTED PRIVATE KEY":
                    throw new IllegalArgumentException("the key is " + m.group(1)
                        + "; write it as unencrypted PKCS#8 (openssl pkcs8 -topk8 -nocrypt)");
                default:
                    // Anything else is ignored, as a comment would be.
                    break;
            }
        }
        if (key == null) {
            throw new IllegalArgumentException("no PRIVATE KEY block");
        }
        if (chain.isEmpty()) {
            throw new IllegalArgumentException("no CERTIFICATE block");
        }
        if (!Arrays.equals(chain.get(0).getPublicKey().getEncoded(), publicOf(key, chain.get(0)))) {
            throw new IllegalArgumentException("the private key does not match the first certificate");
        }
        return new KeyPairEntry(key, chain.toArray(new X509Certificate[0]));
    }

    static X509Certificate parseCertificate(String text) throws Exception {
        Matcher m = PEM_BLOCK.matcher(text);
        while (m.find()) {
            if ("CERTIFICATE".equals(m.group(1))) {
                byte[] der = Base64.getMimeDecoder().decode(m.group(2));
                return (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(der));
            }
        }
        throw new IllegalArgumentException("no CERTIFICATE block");
    }

    /** File name for an alias; {@code taken} avoids two aliases sharing one. */
    static String fileName(String alias, Set<String> taken) {
        String slug = alias.trim().toLowerCase(java.util.Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (slug.isEmpty()) {
            slug = "entry";
        }
        if (slug.length() > 80) {
            slug = slug.substring(0, 80);
        }
        String name = slug + ".pem";
        for (int i = 2; taken.contains(name); i++) {
            name = slug + "-" + i + ".pem";
        }
        taken.add(name);
        return name;
    }

    private static PrivateKey privateKey(byte[] pkcs8) throws Exception {
        for (String alg : new String[] {"RSA", "EC", "Ed25519", "EdDSA", "DSA"}) {
            try {
                return KeyFactory.getInstance(alg).generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
            } catch (Exception ignored) {
                // the next algorithm
            }
        }
        throw new IllegalArgumentException("the private key is not RSA, EC, EdDSA or DSA PKCS#8");
    }

    /**
     * The public half the private key belongs with, for the match check: derived by
     * signing a probe, which works for every algorithm without per-type key maths.
     */
    private static byte[] publicOf(PrivateKey key, X509Certificate cert) {
        try {
            String alg = cert.getPublicKey().getAlgorithm();
            String sigAlg = "RSA".equals(alg) ? "SHA256withRSA" : "EC".equals(alg) ? "SHA256withECDSA"
                : "DSA".equals(alg) ? "SHA256withDSA" : "Ed25519";
            byte[] probe = "git-sync key match".getBytes(StandardCharsets.UTF_8);
            java.security.Signature s = java.security.Signature.getInstance(sigAlg);
            s.initSign(key);
            s.update(probe);
            byte[] sig = s.sign();
            java.security.Signature v = java.security.Signature.getInstance(sigAlg);
            v.initVerify(cert.getPublicKey());
            v.update(probe);
            return v.verify(sig) ? cert.getPublicKey().getEncoded() : new byte[0];
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private static X509Certificate[] x509(Certificate[] certs) {
        X509Certificate[] out = new X509Certificate[certs.length];
        for (int i = 0; i < certs.length; i++) {
            out[i] = (X509Certificate) certs[i];
        }
        return out;
    }

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n"
            + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der)
            + "\n-----END " + type + "-----\n";
    }
}
