#!/usr/bin/env bash
#
# Builds the Git Sync engine extension into dist/gitsync-<version>.zip.
#
#   ./plugins/oie-git-sync/build.sh
#
# No local JDK or Maven required: it compiles inside a container and takes the
# engine jars straight out of the image this repo builds, so it is always
# compiled against exactly the engine it will run on. That matters because the
# jars are not published to Maven Central -- the usual alternative is a
# third-party mirror pinned to some other version.
#
# Environment:
#   OIE_IMAGE      image to take engine jars from  (default oie/engine:4.6.0)
#   JDK_IMAGE      compiler image                  (default eclipse-temurin:21-jdk)
#   PLUGIN_VERSION version stamped into plugin.xml  (default 0.1.0)

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="${HERE}"

OIE_IMAGE="${OIE_IMAGE:-oie/engine:4.6.0}"
JDK_IMAGE="${JDK_IMAGE:-eclipse-temurin:21-jdk}"
PLUGIN_VERSION="${PLUGIN_VERSION:-0.1.0}"

# Must match the engine the extension is installed on, or ExtensionLoader
# refuses it outright -- compatibility is an exact string match.
MIRTH_VERSION="${MIRTH_VERSION:-4.6.0}"

BUILD="${HERE}/build"
LIBS="${BUILD}/libs"
DIST="${HERE}/dist"

log() { printf '==> %s\n' "$*"; }

# JGit, plus the two dependencies it needs at runtime. These jars get loaded
# into the engine's JVM, so each download is verified against the SHA-1 that
# Maven Central publishes beside it -- an artifact changing under us would be
# arbitrary code execution inside the engine.
#
# 6.x is the last line with a Java 11 baseline; it runs fine on 21 and avoids
# the module churn in 7.x.
#
# Core JGit contains no SSH transport at all, so ssh.jsch is required for SSH
# remotes. It is chosen over ssh.apache deliberately: the engine already ships
# jsch-2.27.7 (the mwiede fork, which keeps the com.jcraft.jsch package), so
# this is one extra jar instead of Apache MINA's five. ssh.jsch is deprecated
# upstream -- if that becomes a problem, switching to ssh.apache means adding
# sshd-osgi and sshd-sftp here and nothing else.
JGIT_VERSION="6.10.1.202505221210-r"

declare -a MAVEN_DEPS=(
  "org/eclipse/jgit/org.eclipse.jgit/${JGIT_VERSION}/org.eclipse.jgit-${JGIT_VERSION}.jar"
  "org/eclipse/jgit/org.eclipse.jgit.ssh.jsch/${JGIT_VERSION}/org.eclipse.jgit.ssh.jsch-${JGIT_VERSION}.jar"
  "com/googlecode/javaewah/JavaEWAH/1.2.3/JavaEWAH-1.2.3.jar"
  "org/slf4j/slf4j-api/1.7.36/slf4j-api-1.7.36.jar"
)

########################################################################
# 1. Engine jars, straight from the image
########################################################################
prepare_libs() {
    mkdir -p "$LIBS"

    if [[ -f "${LIBS}/.engine-jars-ok" ]]; then
        log "engine jars already extracted (rm -rf ${BUILD} to refresh)"
        return 0
    fi

    log "extracting engine jars from ${OIE_IMAGE}"
    # A throwaway container is the only way to read an image's filesystem
    # without a running one; `docker create` does not start it.
    local cid
    cid="$(docker create "$OIE_IMAGE" /bin/true)"
    trap 'docker rm -f "$cid" >/dev/null 2>&1 || true' RETURN

    local jar
    for jar in \
        server-lib/mirth-server.jar \
        server-lib/mirth-client-core.jar \
        server-lib/mirth-crypto.jar \
        server-lib/xstream-1.4.20.jar \
        client-lib/donkey-model.jar \
        client-lib/log4j-api-2.25.3.jar \
        client-lib/javax.ws.rs-api-2.0.1.jar \
        client-lib/swagger-annotations-2.0.10.jar \
        client-lib/jersey-media-multipart-2.22.1.jar \
        server-lib/jsch-2.27.7.jar
    do
        docker cp "${cid}:/opt/engine/${jar}" "${LIBS}/$(basename "$jar")" >/dev/null
    done
    docker cp "${cid}:/opt/engine/server-lib/donkey/donkey-server.jar" "${LIBS}/" >/dev/null
    docker cp "${cid}:/opt/engine/server-lib/javax/javax.servlet-api-3.1.0.jar" "${LIBS}/" >/dev/null

    touch "${LIBS}/.engine-jars-ok"
    log "extracted $(ls -1 "${LIBS}"/*.jar | wc -l) engine jars"
}

########################################################################
# 2. Third-party jars that ship inside the extension
########################################################################
fetch_maven_deps() {
    mkdir -p "${LIBS}/bundled"
    local path url name expected actual
    for path in "${MAVEN_DEPS[@]}"; do
        name="$(basename "$path")"
        if [[ -f "${LIBS}/bundled/${name}" ]]; then continue; fi
        url="https://repo1.maven.org/maven2/${path}"
        log "downloading ${name}"
        curl --fail --silent --show-error --location --retry 3 \
            -o "${LIBS}/bundled/${name}" "$url"

        # Central publishes <artifact>.sha1 beside every artifact. Verifying
        # against it catches corruption and substitution without this script
        # carrying a hardcoded digest that nobody ever re-checks.
        expected="$(curl --fail --silent --show-error --location --retry 3 "${url}.sha1" | tr -d '[:space:]')"
        actual="$(sha1sum "${LIBS}/bundled/${name}" | cut -d' ' -f1)"
        if [[ -z "$expected" || "$expected" != "$actual" ]]; then
            rm -f "${LIBS}/bundled/${name}"
            printf 'checksum mismatch for %s: published %s, got %s\n' \
                "$name" "${expected:-<none>}" "$actual" >&2
            exit 1
        fi
        log "  sha1 verified"
    done

    log "bundled jars:"
    (cd "${LIBS}/bundled" && sha256sum ./*.jar | sed 's/^/    /')
}

########################################################################
# 3. Compile
########################################################################
compile() {
    log "compiling with ${JDK_IMAGE}"
    rm -rf "${BUILD}/classes"
    mkdir -p "${BUILD}/classes"

    # Paths are passed relative to the mount so this works the same on Windows.
    MSYS_NO_PATHCONV=1 docker run --rm \
        -v "${HERE}:/p" \
        -w /p \
        "$JDK_IMAGE" \
        sh -c '
            set -e
            CP=$(ls build/libs/*.jar build/libs/bundled/*.jar 2>/dev/null | tr "\n" ":")
            find src -name "*.java" > build/sources.txt
            echo "    $(wc -l < build/sources.txt) source files"
            javac -Xlint:-options -source 17 -target 17 \
                -cp "$CP" -d build/classes @build/sources.txt
        '
    log "compiled"
}

########################################################################
# 4. Package
########################################################################
package() {
    local stage="${BUILD}/stage/gitsync"
    rm -rf "${BUILD}/stage"
    mkdir -p "${stage}/libs" "${DIST}"

    # One jar for the whole extension. The engine's extension classloader does
    # not care about the client/server/shared split unless the Swing client
    # needs its own half, and this plugin's UI is the web console.
    MSYS_NO_PATHCONV=1 docker run --rm -v "${HERE}:/p" -w /p "$JDK_IMAGE" \
        sh -c 'cd build/classes && jar cf ../stage/gitsync/libs/gitsync-server.jar .'

    cp "${LIBS}"/bundled/*.jar "${stage}/libs/"

    # plugin.xml carries the version and the compatibility string, both of which
    # have to be right or the extension is silently not loaded.
    sed -e "s|@PLUGIN_VERSION@|${PLUGIN_VERSION}|g" \
        -e "s|@MIRTH_VERSION@|${MIRTH_VERSION}|g" \
        -e "s|@JGIT_VERSION@|${JGIT_VERSION}|g" \
        "${HERE}/plugin.xml.in" > "${stage}/plugin.xml"

    # The console UI half travels inside the extension, so one install delivers
    # both (the pattern documented in the web administrator's PLUGINS.md).
    cp -a "${HERE}/webadmin" "${stage}/webadmin"

    local zip="${DIST}/gitsync-${PLUGIN_VERSION}.zip"
    rm -f "$zip"
    MSYS_NO_PATHCONV=1 docker run --rm -v "${HERE}:/p" -w /p "$JDK_IMAGE" \
        sh -c "cd build/stage && jar cfM ../../dist/gitsync-${PLUGIN_VERSION}.zip gitsync"

    log "built ${zip#"$ROOT/"}"
    sha256sum "$zip" | sed 's/^/    /'
}

prepare_libs
fetch_maven_deps
compile
package

cat <<EOF

Install it by dropping the zip in extensions/ and restarting:

    cp $(printf '%s' "${DIST}/gitsync-${PLUGIN_VERSION}.zip" | sed "s|${ROOT}/||") extensions/
    docker compose up -d --force-recreate engine
    ./scripts/oie-check-extensions.sh "Git Sync"
EOF
