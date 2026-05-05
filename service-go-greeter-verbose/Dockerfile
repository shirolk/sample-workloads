FROM golang:1.21-alpine AS builder

WORKDIR /app

COPY . .

RUN go build -o go-greeter-verbose .

FROM alpine:latest

WORKDIR /app

COPY --from=builder /app/go-greeter-verbose .

RUN adduser \
    --disabled-password \
    --gecos "" \
    --home "/nonexistent" \
    --shell "/sbin/nologin" \
    --no-create-home \
    --uid 10014 \
    "choreo"

USER 10014

ENTRYPOINT ["./go-greeter-verbose"]
