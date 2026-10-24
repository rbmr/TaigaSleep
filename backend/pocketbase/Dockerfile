FROM alpine:3.20

ARG POCKETBASE_VERSION=0.40.6
ARG TARGETARCH=amd64

RUN apk add --no-cache ca-certificates curl unzip tzdata \
    && addgroup -S taiga \
    && adduser -S -G taiga -h /pb taiga

RUN curl -fsSL -o /tmp/pocketbase.zip \
      "https://github.com/pocketbase/pocketbase/releases/download/v${POCKETBASE_VERSION}/pocketbase_${POCKETBASE_VERSION}_linux_${TARGETARCH}.zip" \
    && unzip -q /tmp/pocketbase.zip pocketbase -d /pb \
    && rm /tmp/pocketbase.zip \
    && chmod +x /pb/pocketbase

COPY pb_migrations /pb/pb_migrations
COPY pb_hooks /pb/pb_hooks
COPY docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh

RUN chmod +x /usr/local/bin/docker-entrypoint.sh \
    && mkdir -p /pb/pb_data \
    && chown -R taiga:taiga /pb

WORKDIR /pb
USER taiga

ENV TAIGA_DATA_DIR=/pb/pb_data \
    TAIGA_HTTP_ADDR=0.0.0.0:8090

EXPOSE 8090
VOLUME ["/pb/pb_data"]

ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]
