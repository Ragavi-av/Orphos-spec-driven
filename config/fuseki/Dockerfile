# Apache Jena Fuseki, built from the official Apache release binaries (Apache-2.0).
# Replaces the unofficial third-party stain/jena-fuseki community image — same
# software, trusted provenance, checksum-verified download. Behaviour mirrors the
# previous image: FUSEKI_BASE=/fuseki, admin password seeded from ADMIN_PASSWORD,
# /$/ping open for health checks, admin API + writes restricted to admin.
FROM eclipse-temurin:21-jre

ENV FUSEKI_VERSION=5.1.0 \
    FUSEKI_HOME=/jena-fuseki \
    FUSEKI_BASE=/fuseki \
    FUSEKI_SHA512=50d33937092e8120d57f503b6e96ef988894602aa060ff945ec3aecf0349b0b22250e158bb379d0300589653dc9d6f3e6eb2b9790b5125144108dd6f19dc41e6

RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends curl ca-certificates gettext-base; \
    rm -rf /var/lib/apt/lists/*; \
    curl -fSL "https://archive.apache.org/dist/jena/binaries/apache-jena-fuseki-${FUSEKI_VERSION}.tar.gz" -o /tmp/fuseki.tar.gz; \
    echo "${FUSEKI_SHA512}  /tmp/fuseki.tar.gz" | sha512sum -c -; \
    mkdir -p "${FUSEKI_HOME}" "${FUSEKI_BASE}"; \
    tar -zxf /tmp/fuseki.tar.gz -C "${FUSEKI_HOME}" --strip-components=1; \
    rm /tmp/fuseki.tar.gz; \
    chmod +x "${FUSEKI_HOME}/fuseki-server"

WORKDIR ${FUSEKI_HOME}
# The official tarball ships no shiro.ini; provide the standard Apache template
# (carries the ${ADMIN_PASSWORD} placeholder the entrypoint substitutes).
COPY shiro.ini ${FUSEKI_HOME}/shiro.ini
COPY docker-entrypoint.sh /docker-entrypoint.sh
RUN chmod +x /docker-entrypoint.sh

EXPOSE 3030
ENTRYPOINT ["/docker-entrypoint.sh"]
CMD ["/jena-fuseki/fuseki-server"]
