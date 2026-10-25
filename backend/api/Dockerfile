FROM golang:1.27-bookworm AS build

WORKDIR /src
COPY api/go.mod api/go.sum ./
RUN go mod download
COPY api/ ./
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/taiga-sleep-api .

FROM alpine:3.22

RUN apk add --no-cache ca-certificates wget \
    && addgroup -S taiga \
    && adduser -S -G taiga -h /app taiga

WORKDIR /app
COPY --from=build /out/taiga-sleep-api /app/taiga-sleep-api
COPY pocketbase/pb_migrations /app/pb_migrations
COPY pocketbase/pb_hooks /app/pb_hooks
COPY api/docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh

RUN chmod +x /usr/local/bin/docker-entrypoint.sh \
    && mkdir -p /pb/pb_data \
    && chown -R taiga:taiga /app /pb

USER taiga

ENV TAIGA_DATA_DIR=/pb/pb_data \
    TAIGA_HTTP_ADDR=0.0.0.0:8090

EXPOSE 8090
VOLUME ["/pb/pb_data"]

ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]
