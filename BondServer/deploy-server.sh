#!/bin/bash
# Bond Server One-Click Deployment Script for Linux
# Compatible with: Ubuntu, Debian, CentOS 7/8/9, RHEL, Fedora, Arch, Alpine
# Usage: chmod +x deploy-server.sh && ./deploy-server.sh [install|start|stop|restart|status|logs|update|uninstall]

set -e

# ── Config ──
BOND_DIR="$HOME/bond"
DB_PASSWORD="${DB_PASSWORD:-bond123456}"
JWT_SECRET="${JWT_SECRET:-$(openssl rand -hex 24)}"
MINIO_ACCESS_KEY="${MINIO_ACCESS_KEY:-bond}"
MINIO_SECRET_KEY="${MINIO_SECRET_KEY:-$(openssl rand -hex 16)}"
# P0：网关↔下游服务 HMAC 共享密钥；缺失则三个服务启动即失败
INTERNAL_GATEWAY_SECRET="${INTERNAL_GATEWAY_SECRET:-$(openssl rand -hex 32)}"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

log()  { echo -e "${CYAN}[*]${NC} $1"; }
ok()   { echo -e "${GREEN}[+]${NC} $1"; }
warn() { echo -e "${YELLOW}[!]${NC} $1"; }
err()  { echo -e "${RED}[-]${NC} $1"; }

# ── OS Detection ──
detect_os() {
    OS_ID=""; OS_VERSION=""; PKG_MGR=""; INIT_SYSTEM=""
    if [ -f /etc/os-release ]; then
        . /etc/os-release
        OS_ID="${ID:-unknown}"; OS_VERSION="${VERSION_ID:-0}"
    fi
    if command -v apt-get &>/dev/null; then PKG_MGR="apt"
    elif command -v dnf &>/dev/null; then PKG_MGR="dnf"
    elif command -v yum &>/dev/null; then PKG_MGR="yum"
    elif command -v apk &>/dev/null; then PKG_MGR="apk"
    elif command -v pacman &>/dev/null; then PKG_MGR="pacman"
    fi
    if [ -d /run/systemd/system ]; then INIT_SYSTEM="systemd"
    elif command -v service &>/dev/null; then INIT_SYSTEM="sysvinit"
    fi
    log "OS: $OS_ID $OS_VERSION | Pkg: $PKG_MGR | Init: $INIT_SYSTEM"
}

# ── Install Docker ──
install_docker() {
    if command -v docker &>/dev/null; then
        ok "Docker already installed: $(docker --version)"
        return
    fi
    log "Installing Docker..."
    case "$OS_ID" in
        ubuntu|debian)
            sudo apt-get update -y
            sudo apt-get install -y ca-certificates curl gnupg
            sudo install -m 0755 -d /etc/apt/keyrings
            curl -fsSL "https://download.docker.com/linux/$OS_ID/gpg" | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg 2>/dev/null || true
            sudo chmod a+r /etc/apt/keyrings/docker.gpg
            echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/$OS_ID $(lsb_release -cs) stable" | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
            sudo apt-get update -y && sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin
            ;;
        centos)
            MAJOR=$(echo "$OS_VERSION" | cut -d. -f1)
            if [ "$MAJOR" -le 7 ]; then
                sudo yum install -y yum-utils
                sudo yum-config-manager --add-repo https://download.docker.com/linux/centos/docker-ce.repo
                sudo yum install -y docker-ce docker-ce-cli containerd.io
                COMPOSE_VERSION=$(curl -s https://api.github.com/repos/docker/compose/releases/latest | grep -oP '"tag_name": "\K(.*)(?=")')
                sudo curl -L "https://github.com/docker/compose/releases/download/${COMPOSE_VERSION}/docker-compose-$(uname -s)-$(uname -m)" -o /usr/local/bin/docker-compose
                sudo chmod +x /usr/local/bin/docker-compose
            else
                sudo dnf -y install dnf-plugins-core
                sudo dnf config-manager --add-repo https://download.docker.com/linux/centos/docker-ce.repo
                sudo dnf install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin
            fi
            ;;
        rhel|rocky|almalinux)
            sudo yum install -y yum-utils 2>/dev/null || sudo dnf -y install dnf-plugins-core
            sudo yum-config-manager --add-repo https://download.docker.com/linux/centos/docker-ce.repo 2>/dev/null || sudo dnf config-manager --add-repo https://download.docker.com/linux/centos/docker-ce.repo
            sudo yum install -y docker-ce docker-ce-cli containerd.io || sudo dnf install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin
            ;;
        *)
            curl -fsSL https://get.docker.com | sudo sh
            ;;
    esac
    if [ "$INIT_SYSTEM" = "systemd" ]; then
        sudo systemctl start docker && sudo systemctl enable docker
    else
        sudo service docker start
    fi
    sudo usermod -aG docker "$USER" 2>/dev/null || true
    ok "Docker installed: $(docker --version 2>/dev/null || echo 'requires re-login')"
}

# ── Check prerequisites ──
check_prerequisites() {
    log "Checking prerequisites..."
    detect_os
    install_docker
    if ! docker compose version &>/dev/null; then
        if ! command -v docker-compose &>/dev/null; then
            err "docker compose not available"; exit 1
        fi
    fi
    ok "Prerequisites met"
}

# ── Configure Docker mirror ──
configure_docker_mirror() {
    log "Configuring Docker mirror..."
    local daemon_json="/etc/docker/daemon.json"
    local mirror_config='{"registry-mirrors":["https://docker.1ms.run","https://docker.xuanyuan.me","https://docker.m.daocloud.io"],"log-driver":"json-file","log-opts":{"max-size":"10m","max-file":"3"}}'
    if [ -f "$daemon_json" ] && grep -q "registry-mirrors" "$daemon_json" 2>/dev/null; then
        ok "Docker mirror already configured"; return
    fi
    sudo mkdir -p /etc/docker
    echo "$mirror_config" | sudo tee "$daemon_json" > /dev/null 2>&1
    ok "Docker mirror configured"
    if [ "$INIT_SYSTEM" = "systemd" ]; then sudo systemctl restart docker 2>/dev/null
    else sudo service docker restart 2>/dev/null; fi
    sleep 3
}

# ── Create directory ──
setup_directories() {
    log "Creating directory structure..."
    mkdir -p "$BOND_DIR" && cd "$BOND_DIR"
    ok "Directory: $BOND_DIR"
}

# ── Generate docker-compose.yml ──
generate_compose() {
    log "Generating docker-compose.yml..."
    cat > docker-compose.yml << 'COMPOSE'
services:
  postgres:
    image: postgres:16
    container_name: bond-postgres
    environment:
      - POSTGRES_PASSWORD=${DB_PASSWORD}
      - POSTGRES_DB=bond
    volumes:
      - pg_data:/var/lib/postgresql/data
    ports:
      - "127.0.0.1:5432:5432"
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U postgres -d bond"]
      interval: 10s
      timeout: 5s
      retries: 10
    restart: unless-stopped

# 回滚到 MySQL 8（仅回滚部署层）：
#   1) 把上面的 postgres 服务换回下方注释块；
#   2) pom 依赖 postgresql -> mysql-connector-j（Flyway 需换回 flyway-mysql 或直接停用）；
#   3) application-*.yml 的 url/driver/username 改回 mysql。
#  mysql:
#    image: mysql:8.0
#    container_name: bond-mysql
#    environment:
#      - MYSQL_ROOT_PASSWORD=${DB_PASSWORD}
#      - MYSQL_DATABASE=bond
#    volumes:
#      - mysql_data:/var/lib/mysql
#    ports:
#      - "3306:3306"
#    healthcheck:
#      test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]
#      interval: 10s
#      timeout: 5s
#      retries: 10
#    restart: unless-stopped

  rustfs:
    image: rustfs/rustfs:latest
    container_name: bond-rustfs
    environment:
      - RUSTFS_ACCESS_KEY=${MINIO_ACCESS_KEY}
      - RUSTFS_SECRET_KEY=${MINIO_SECRET_KEY}
    volumes:
      - rustfs_data:/data
    ports:
      - "127.0.0.1:9000:9000"
      - "127.0.0.1:9001:9001"
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:9000/health/ready"]
      interval: 10s
      timeout: 5s
      retries: 10
    restart: unless-stopped

  bond-auth:
    image: bitnamilegacy/java:21
    container_name: bond-auth
    working_dir: /app
    volumes:
      - ./bond-auth.jar:/app/app.jar:ro
      - ./application-auth.yml:/app/application.yml:ro
    environment:
      - JWT_SECRET=${JWT_SECRET}
      - WORKER_ID=1
      - INTERNAL_GATEWAY_SECRET=${INTERNAL_GATEWAY_SECRET}
      # task-152 用户头像：复用 RustFS 凭据（独立 bucket），容器内网直连
      - STORAGE_ENDPOINT=http://rustfs:9000
      - STORAGE_ACCESS_KEY=${MINIO_ACCESS_KEY}
      - STORAGE_SECRET_KEY=${MINIO_SECRET_KEY}
    command: ["java", "-jar", "app.jar", "--spring.config.additional-location=file:/app/application.yml"]
    ports:
      - "127.0.0.1:8081:8081"
    depends_on:
      postgres: {condition: service_healthy}
    restart: unless-stopped

  bond-transfer:
    image: bitnamilegacy/java:21
    container_name: bond-transfer
    working_dir: /app
    volumes:
      - ./bond-transfer.jar:/app/app.jar:ro
      - ./application-transfer.yml:/app/application.yml:ro
    environment:
      - JWT_SECRET=${JWT_SECRET}
      - WORKER_ID=2
      - INTERNAL_GATEWAY_SECRET=${INTERNAL_GATEWAY_SECRET}
    command: ["java", "-jar", "app.jar", "--spring.config.additional-location=file:/app/application.yml"]
    ports:
      - "127.0.0.1:8082:8082"
    depends_on:
      postgres: {condition: service_healthy}
      rustfs: {condition: service_healthy}
    restart: unless-stopped

  bond-gateway:
    image: bitnamilegacy/java:21
    container_name: bond-gateway
    working_dir: /app
    volumes:
      - ./bond-gateway.jar:/app/app.jar:ro
      - ./application-gateway.yml:/app/application.yml:ro
    environment:
      - JWT_SECRET=${JWT_SECRET}
      - INTERNAL_GATEWAY_SECRET=${INTERNAL_GATEWAY_SECRET}
    command: ["java", "-jar", "app.jar", "--spring.config.additional-location=file:/app/application.yml"]
    ports:
      - "127.0.0.1:8080:8080"
    depends_on:
      bond-auth: {condition: service_started}
      bond-transfer: {condition: service_started}
    restart: unless-stopped

networks:
  default:
    name: bond-network

volumes:
  pg_data:
#  mysql_data:   # 回滚 MySQL 时恢复
  rustfs_data:
COMPOSE
    ok "docker-compose.yml generated"
}

# ── Generate configs ──
generate_configs() {
    log "Generating config files..."
    cat > application-auth.yml << EOF
server:
  port: 8081
spring:
  datasource:
    url: jdbc:postgresql://postgres:5432/bond
    username: postgres
    password: ${DB_PASSWORD}
    driver-class-name: org.postgresql.Driver
  flyway:
    enabled: true
    baseline-on-migrate: true
    locations: classpath:db/migration
  application:
    name: bond-auth
  servlet:
    multipart: {max-file-size: 2MB, max-request-size: 3MB}
mybatis-plus:
  mapper-locations: classpath:mapper/*.xml
  configuration: {map-underscore-to-camel-case: true}
jwt:
  secret: ${JWT_SECRET}
storage:
  endpoint: http://rustfs:9000
  access-key: ${MINIO_ACCESS_KEY}
  secret-key: ${MINIO_SECRET_KEY}
  region: us-east-1
  avatar-bucket: bond-avatar
EOF

    cat > application-transfer.yml << EOF
server:
  port: 8082
spring:
  datasource:
    url: jdbc:postgresql://postgres:5432/bond
    username: postgres
    password: ${DB_PASSWORD}
    driver-class-name: org.postgresql.Driver
  flyway:
    enabled: true
    baseline-on-migrate: true
    locations: classpath:db/migration
  application:
    name: bond-transfer
  servlet:
    multipart: {max-file-size: 5GB, max-request-size: 5GB}
mybatis-plus:
  mapper-locations: classpath:mapper/*.xml
  configuration: {map-underscore-to-camel-case: true}
storage:
  endpoint: http://rustfs:9000
  access-key: ${MINIO_ACCESS_KEY}
  secret-key: ${MINIO_SECRET_KEY}
  bucket: bond-transfer
  region: us-east-1
  path-style: true
EOF

    cat > application-gateway.yml << EOF
server:
  port: 8080
spring:
  application:
    name: bond-gateway
  cloud:
    gateway:
      httpclient:
        connect-timeout: 600000
        response-timeout: 43200s
      routes:
        - id: bond-auth
          uri: http://bond-auth:8081
          predicates: [Path=/api/auth/**,/api/devices/**,/api/friends/**,/api/users/**]
        - id: bond-transfer
          uri: http://bond-transfer:8082
          # /dav 与 /dav/** 由网关整体放行（无 JWT）；身份只来自 WebDAV Basic，见 docs/webdav-design.md §10
          predicates: [Path=/api/transfer/**,/api/workspaces/**,/api/files/**,/api/trash/**,/api/entitlements/**,/api/webdav/**,/api/documents/**,/api/chat/**,/api/announcements/**,/api/admin/**,/api/invite/**,/api/redeem,/api/shares/**,/s/**,/i/**,/dav,/dav/**]
      default-filters:
        - DedupeResponseHeader=Access-Control-Allow-Origin Access-Control-Allow-Credentials, RETAIN_UNIQUE
      globalcors:
        cors-configurations:
          '[/**]':
            allowedOriginPatterns: "*"
            allowedMethods: "*"
            allowedHeaders: "*"
            allowCredentials: true
EOF

    cat > .env << EOF
DB_PASSWORD=${DB_PASSWORD}
JWT_SECRET=${JWT_SECRET}
MINIO_ACCESS_KEY=${MINIO_ACCESS_KEY}
MINIO_SECRET_KEY=${MINIO_SECRET_KEY}
INTERNAL_GATEWAY_SECRET=${INTERNAL_GATEWAY_SECRET}
EOF
    chmod 600 .env
    ok "Config files generated"
}

# ── Copy JAR files ──
copy_jars() {
    log "Checking JAR files..."
    local script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
    local missing=0
    for jar in bond-auth bond-transfer bond-gateway; do
        if [ -f "${BOND_DIR}/${jar}.jar" ]; then
            ok "${jar}.jar already in ${BOND_DIR}"
        elif [ -f "${script_dir}/${jar}.jar" ]; then
            cp "${script_dir}/${jar}.jar" "${BOND_DIR}/${jar}.jar"
            ok "${jar}.jar copied from script directory"
        else
            err "${jar}.jar not found"
            missing=1
        fi
    done
    if [ $missing -eq 1 ]; then
        echo "Place JAR files in the same directory as this script, or in ${BOND_DIR}/"
        exit 1
    fi
    ok "JAR files ready"
}

# ── Start services ──
start_services() {
    log "Starting services..."
    cd "$BOND_DIR"
    docker compose up -d 2>&1 | while read line; do echo "  $line"; done
    echo ""
    log "Waiting for services to be ready..."
    sleep 10
    local retries=30
    while [ $retries -gt 0 ]; do
        if curl -sf http://localhost:8080/api/auth/me > /dev/null 2>&1; then
            ok "All services are ready!"; break
        fi
        retries=$((retries - 1)); sleep 2
    done
    if [ $retries -eq 0 ]; then warn "Services may still be starting. Check: docker compose logs"; fi
    echo ""
    echo "══════════════════════════════════════════"
    echo "  Bond Server Deployed Successfully"
    echo "══════════════════════════════════════════"
    echo ""
    echo "  API Gateway:   http://localhost:8080"
    echo "  Auth Service:  http://localhost:8081"
    echo "  Transfer:      http://localhost:8082"
    echo "  RustFS Console: http://localhost:9001"
    echo ""
    echo "  Credentials: ${BOND_DIR}/.env"
    echo ""
}

# ── Stop/Restart/Status/Logs/Update/Uninstall ──
stop_services() { cd "$BOND_DIR" && docker compose down && ok "Services stopped"; }
show_status() { cd "$BOND_DIR" && docker compose ps; }
show_logs() { cd "$BOND_DIR"; if [ -n "$2" ]; then docker compose logs -f "$2"; else docker compose logs -f; fi; }
update_jars() {
    log "Updating JARs..."
    cd "$BOND_DIR"
    docker compose stop bond-auth bond-transfer bond-gateway
    local script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
    for jar in bond-auth bond-transfer bond-gateway; do
        if [ -f "${script_dir}/${jar}.jar" ]; then
            cp "${script_dir}/${jar}.jar" "${BOND_DIR}/${jar}.jar"
            ok "${jar}.jar updated"
        fi
    done
    docker compose up -d bond-auth bond-transfer bond-gateway
    ok "Services restarted"
}
uninstall() {
    warn "This will remove all Bond data including database!"
    read -p "Are you sure? (y/N): " confirm
    if [ "$confirm" != "y" ] && [ "$confirm" != "Y" ]; then echo "Cancelled"; exit 0; fi
    cd "$BOND_DIR" && docker compose down -v
    cd / && rm -rf "$BOND_DIR"
    ok "Bond uninstalled"
}

# ── Main ──
ACTION="${1:-install}"
case "$ACTION" in
    install)
        echo ""
        echo "══════════════════════════════════════════"
        echo "  Bond Server Installer"
        echo "══════════════════════════════════════════"
        echo ""
        check_prerequisites
        configure_docker_mirror
        setup_directories
        generate_compose
        generate_configs
        copy_jars
        start_services
        ;;
    start) start_services ;;
    stop) stop_services ;;
    restart) stop_services; start_services ;;
    status) show_status ;;
    logs) show_logs "$@" ;;
    update) update_jars ;;
    uninstall) uninstall ;;
    *) echo "Usage: $0 {install|start|stop|restart|status|logs|update|uninstall}"; exit 1 ;;
esac
