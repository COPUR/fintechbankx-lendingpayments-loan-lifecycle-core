#!/usr/bin/env bash
# Chart lint, render and refuse probes for deploy/helm/loan-lifecycle-service, run by the deploy/helm job
# in .github/workflows/deployability.yml. It lives here, not inline, because GitHub
# turns a run: block that contains an expression into one expression and refuses
# the whole workflow above 21,000 characters (zero-job startup failure). The image
# tag comes from GITHUB_SHA, which the runner sets to the same value as github.sha.
set -e
: "${GITHUB_SHA:?GITHUB_SHA must be set (the runner sets it)}"
# PyYAML for the checks below: pinned by hash, installed from wheels only, into a venv under RUNNER_TEMP
# when the runner's Python lacks it (PEP 668 blocks pip --user on the fbx-ci Ubuntu VM).
if ! python3 -c "import yaml" 2>/dev/null; then
  : "${RUNNER_TEMP:?RUNNER_TEMP must be set: the PyYAML venv is created under the runner temp directory}"
  venv="$RUNNER_TEMP/fbx-chart-probes-venv"
  python3 -m venv "$venv"
  cat > "$venv/requirements.txt" <<'REQUIREMENTS'
pyyaml==6.0.2 \
    --hash=sha256:0a9a2848a5b7feac301353437eb7d5957887edbf81d56e903999a75a3d743086 \
    --hash=sha256:29717114e51c84ddfba879543fb232a6ed60086602313ca38cce623c1d62cfbf \
    --hash=sha256:8824b5a04a04a047e72eea5cec3bc266db09e35de6bdfe34c9436ac5ee27d237 \
    --hash=sha256:7c36280e6fb8385e520936c3cb3b8042851904eba0e58d277dca80a5cfed590b \
    --hash=sha256:ec031d5d2feb36d1d1a24380e4db6d43695f3748343d99434e6f5f9156aaa2ed \
    --hash=sha256:936d68689298c36b53b29f23c6dbb74de12b4ac12ca6cfe0e047bedceea56180 \
    --hash=sha256:23502f431948090f597378482b4812b0caae32c22213aecf3b55325e049a6c68 \
    --hash=sha256:2e99c6826ffa974fe6e27cdb5ed0021786b03fc98e5ee3c5bfe1fd5015f42b99 \
    --hash=sha256:a4d3091415f010369ae4ed1fc6b79def9416358877534caf6a0fdd2146c87a3e \
    --hash=sha256:cc1c1159b3d456576af7a3e4d1ba7e6924cb39de8f67111c735f6fc832082774 \
    --hash=sha256:1e2120ef853f59c7419231f3bf4e7021f1b936f6ebd222406c3b60212205d2ee \
    --hash=sha256:5d225db5a45f21e78dd9358e58a98702a0302f2659a3c6cd320564b75b86f47c \
    --hash=sha256:5ac9328ec4831237bec75defaf839f7d4564be1e6b25ac710bd1a96321cc8317 \
    --hash=sha256:3ad2a3decf9aaba3d29c8f537ac4b243e36bef957511b4766cb0057d32b0be85 \
    --hash=sha256:ff3824dc5261f50c9b0dfb3be22b4567a6f938ccce4587b38952d85fd9e9afe4 \
    --hash=sha256:797b4f722ffa07cc8d62053e4cff1486fa6dc094105d13fea7b1de7d8bf71c9e \
    --hash=sha256:11d8f3dd2b9c1207dcaf2ee0bbbfd5991f571186ec9cc78427ba5bd32afae4b5 \
    --hash=sha256:e10ce637b18caea04431ce14fabcf5c64a1c61ec9c56b071a4b7ca131ca52d44 \
    --hash=sha256:c70c95198c015b85feafc136515252a261a84561b7b1d51e3384e0655ddf25ab \
    --hash=sha256:ce826d6ef20b1bc864f0a68340c8b3287705cae2f8b4b1d932177dcc76721725 \
    --hash=sha256:1f71ea527786de97d1a0cc0eacd1defc0985dcf6b3f17bb77dcfc8c34bec4dc5 \
    --hash=sha256:9b22676e8097e9e22e36d6b7bda33190d0d400f345f23d4065d48f4ca7ae0425 \
    --hash=sha256:80bab7bfc629882493af4aa31a4cfa43a4c57c83813253626916b8c7ada83476 \
    --hash=sha256:0833f8694549e586547b576dcfaba4a6b55b9e96098b36cdc7ebefe667dfed48 \
    --hash=sha256:8b9c7197f7cb2738065c481a0461e50ad02f18c78cd75775628afb4d7137fb3b \
    --hash=sha256:ef6107725bd54b262d6dedcc2af448a266975032bc85ef0172c5f059da6325b4 \
    --hash=sha256:7e7401d0de89a9a855c839bc697c079a4af81cf878373abd7dc625847d25cbd8 \
    --hash=sha256:efdca5630322a10774e8e98e1af481aad470dd62c3170801852d752aa7a783ba \
    --hash=sha256:50187695423ffe49e2deacb8cd10510bc361faac997de9efef88badc3bb9e2d1 \
    --hash=sha256:0ffe8360bab4910ef1b9e87fb812d8bc0a308b0d0eef8c8f44e0254ab3b07133 \
    --hash=sha256:17e311b6c678207928d649faa7cb0d7b4c26a0ba73d41e99c4fff6b6c3276484 \
    --hash=sha256:70b189594dbe54f75ab3a1acec5f1e3faa7e8cf2f1e08d9b561cb41b845f69d5 \
    --hash=sha256:41e4e3953a79407c794916fa277a82531dd93aad34e29c2a514c2c0c5fe971cc \
    --hash=sha256:68ccc6023a3400877818152ad9a1033e3db8625d899c72eacb5a668902e4d652 \
    --hash=sha256:bc2fa7c6b47d6bc618dd7fb02ef6fdedb1090ec036abab80d4681424b84c1183 \
    --hash=sha256:8388ee1976c416731879ac16da0aff3f63b286ffdd57cdeb95f3f2e085687563 \
    --hash=sha256:24471b829b3bf607e04e88d79542a9d48bb037c2267d7927a874e6c205ca7e9a \
    --hash=sha256:d7fded462629cfa4b685c5416b949ebad6cec74af5e2d42905d41e257e0869f5 \
    --hash=sha256:d84a1718ee396f54f3a086ea0a66d8e552b2ab2017ef8b420e92edbc841c352d \
    --hash=sha256:9056c1ecd25795207ad294bcf39f2db3d845767be0ea6e6a34d856f006006083 \
    --hash=sha256:82d09873e40955485746739bcb8b4586983670466c23382c19cffecbf1fd8706 \
    --hash=sha256:43fa96a3ca0d6b1812e01ced1044a003533c47f6ee8aca31724f78e93ccc089a \
    --hash=sha256:01179a4a8559ab5de078078f37e5c1a30d76bb88519906844fd7bdea1b7729ff \
    --hash=sha256:688ba32a1cffef67fd2e9398a2efebaea461578b0923624778664cc1c914db5d \
    --hash=sha256:a8786accb172bd8afb8be14490a16625cbc387036876ab6ba70912730faf8e1f \
    --hash=sha256:d8e03406cac8513435335dbab54c0d385e4a49e4945d2909a581c83647ca0290 \
    --hash=sha256:f753120cb8181e736c57ef7636e83f31b9c0d1722c516f7e86cf15b7aa57ff12 \
    --hash=sha256:3b1fdb9dc17f5a7677423d508ab4f243a726dea51fa5e70992e59a7411c89d19 \
    --hash=sha256:0b69e4ce7a131fe56b7e4d770c67429700908fc0752af059838b1cfb41960e4e \
    --hash=sha256:a9f8c2e67970f13b16084e04f134610fd1d374bf477b17ec1599185cf611d725 \
    --hash=sha256:6395c297d42274772abc367baaa79683958044e5d3835486c16da75d2a694631 \
    --hash=sha256:39693e1f8320ae4f43943590b49779ffb98acb81f788220ea932a6b6c51004d8
REQUIREMENTS
  "$venv/bin/python" -m pip install --quiet --only-binary=:all: --require-hashes -r "$venv/requirements.txt"
  export PATH="$venv/bin:$PATH"
fi
chart=deploy/helm/loan-lifecycle-service
# Dummy values for the settings the chart requires (no defaults);
# real ones come from the environment's values file.
args="--set image.repository=ci/loan-lifecycle-service --set image.tag=${GITHUB_SHA}"
args="$args --set externalSecret.remoteSecretName=dev/loan-lifecycle-service/db-app --set externalSecret.serviceClientSecretName=dev/loan-lifecycle-service/oidc-client"
args="$args --set migration.remoteSecretName=dev/loan-lifecycle-service/db-migration"
args="$args --set-string config.DB_URL=jdbc:postgresql://ci-db:5432/db_ln_loan_lifecycle_ci?sslmode=verify-full&sslrootcert=/etc/fintechbankx/rds-ca/global-bundle.pem"
args="$args --set config.KAFKA_BOOTSTRAP_SERVERS=ci-kafka:9098"
args="$args --set config.OIDC_ISSUER_URI=https://ci-idp/realms/fintechbankx"
args="$args --set config.OIDC_JWK_SET_URI=https://ci-idp/realms/fintechbankx/protocol/openid-connect/certs"
args="$args --set config.OIDC_TOKEN_URI=https://ci-idp/realms/fintechbankx/protocol/openid-connect/token"
args="$args --set config.CUSTOMER_SERVICE_BASE_URL=http://ci-customer:8080"
args="$args --set config.ACCOUNTS_SERVICE_BASE_URL=http://ci-accounts:8080"
args="$args --set config.CUSTOMER_CREDIT_ADAPTER=http --set config.CUSTOMER_CREDIT_LEDGER_CURRENCY=XTS"
helm lint "$chart" $args
helm template ci "$chart" $args > /dev/null
# refuse <expected ERE> <helm args...>: the render must fail, and for that reason, so a probe cannot
# pass because helm failed to parse it or another rule fired first.
refuse() {
  local expect="$1"; shift
  local out
  if out="$(helm template ci "$chart" $args "$@" 2>&1 >/dev/null)"; then
    echo "chart rendered with $*" >&2; exit 1
  fi
  if ! grep -qE "$expect" <<<"$out"; then
    echo "chart refused $* for another reason than '$expect': $out" >&2; exit 1
  fi
}
helm template ci "$chart" -f "$chart/values-dev.yaml" $args > /dev/null
# ESO may read only secret:<env>/*: a "<env>-<slug>/db-app" key must stop the render.
if helm template ci "$chart" $args --set externalSecret.remoteSecretName=dev-loan-lifecycle-service/db-app > /dev/null 2>&1; then
  echo "chart rendered a Secrets Manager key outside <env>/loan-lifecycle-service/" >&2
  exit 1
fi
# The pods verify Aurora's certificate against the platform RDS CA bundle.
rendered="$(helm template ci "$chart" $args)"
grep -q 'name: "rds-ca-bundle"' <<<"$rendered"
grep -q 'mountPath: "/etc/fintechbankx/rds-ca"' <<<"$rendered"
# cicd-templates 4f0f266: mounted at /etc/fintechbankx/rds-ca, exported as DB_SSL_ROOT_CERT,
# in the pods and the migration Job.
test "$(grep -c 'name: DB_SSL_ROOT_CERT' <<<"$rendered")" -eq 2
grep -A1 'name: DB_SSL_ROOT_CERT' <<<"$rendered" | grep -q 'value: "/etc/fintechbankx/rds-ca/global-bundle.pem"'
# Round 6 (guardrail 4a, loan #14 review; cicd-templates 73e1881 fbx.validateJdbcUrl, vendored in
# _fbx_helpers.tpl): the JDBC URL is parsed the way PgJDBC parses it, not searched by substring.
# sslmode (lower case) exactly once and verify-full, sslrootcert exactly once and the mounted bundle,
# no key that bypasses verification, no percent-encoded '=' or '&', no TLS key before the '?'.
ca=/etc/fintechbankx/rds-ca/global-bundle.pem
for url in "jdbc:postgresql://ci-db:5432/db?sslmode=verify-full&sslrootcert=/etc/ssl/rds/global-bundle.pem" \
           "jdbc:postgresql://ci-db:5432/db?sslmode=require" \
           "jdbc:postgresql://ci-db:5432/db?sslmode=verify-full" \
           "jdbc:postgresql://ci-db:5432/db?sslrootcert=$ca" \
           "jdbc:postgresql://ci-db:5432/db?sslmode=verify-full&sslrootcert=$ca&sslmode=disable" \
           "jdbc:postgresql://ci-db:5432/db?sslrootcert=/tmp/decoy.pem&sslmode=verify-full&sslrootcert=$ca" \
           "jdbc:postgresql://ci-db:5432/db?sslmode=verify-full&sslrootcert=$ca&sslfactory=org.postgresql.ssl.NonValidatingFactory" \
           "jdbc:postgresql://ci-db:5432/db?sslmode=verify-full&sslrootcert=$ca&sslfactoryarg=x" \
           "jdbc:postgresql://ci-db:5432/db?sslmode=verify-full&sslrootcert=$ca&sslhostnameverifier=x.Y" \
           "jdbc:postgresql://ci-db:5432/db?sslmode=verify-full&sslrootcert=$ca&sslpasswordcallback=x.Y" \
           "jdbc:postgresql://ci-db:5432/db?sslmode=verify-full&sslrootcert=$ca&service=other" \
           "jdbc:postgresql://ci-db:5432/db?sslmode=require&applicationName=sslmode=verify-full&sslrootcert=$ca" \
           "jdbc:postgresql://ci-db:5432/db?SSLMODE=verify-full&sslrootcert=$ca" \
           "jdbc:postgresql://ci-db:5432/db?sslmode=verify-full&SSLROOTCERT=$ca" \
           "jdbc:postgresql://ci-db:5432/db?sslmode%3Dverify-full&sslrootcert=$ca" \
           "jdbc:postgresql://ci-db:5432/db?sslmode=verify-full%26sslrootcert=$ca" \
           "jdbc:postgresql://ci-db:5432/db?ssl%6dode=verify-full&sslrootcert=$ca" \
           "jdbc:postgresql://ci-db:5432/sslmode=verify-full?sslrootcert=$ca" \
           "jdbc:postgresql://ci-db:5432/db;sslmode=disable?sslmode=verify-full&sslrootcert=$ca" \
           "jdbc:mysql://ci-db:3306/db?sslMode=VERIFY_IDENTITY" \
           "jdbc:postgresql://ci-db:5432/db?sslmode=verify-full&sslrootcert=$ca&a b=c"; do
  if helm template ci "$chart" $args --set-string "config.DB_URL=$url" > /dev/null 2>&1; then
    echo "chart rendered DB_URL $url" >&2
    exit 1
  fi
done
# Any other config value that is a PostgreSQL JDBC URL gets the same parse.
if helm template ci "$chart" $args --set-string "config.REPORTING_DB_URL=jdbc:postgresql://other:5432/db?sslmode=disable" > /dev/null 2>&1; then
  echo "chart rendered a second JDBC URL without verify-full" >&2
  exit 1
fi
# The parse accepts the two valid orders and a wrapper driver prefix.
helm template ci "$chart" $args --set-string "config.DB_URL=jdbc:postgresql://ci-db:5432/db?sslrootcert=$ca&sslmode=verify-full&ApplicationName=loan" > /dev/null
helm template ci "$chart" $args --set-string "config.DB_URL=jdbc:aws-wrapper:postgresql://ci-db:5432/db?sslmode=verify-full&sslrootcert=$ca" > /dev/null
# Round 5 (governance 2b and 4) and round 6 (guardrail 4a, loan #14 review): no value may redirect
# Spring's configuration, name a profile, bypass the DB_URL guard, switch the startup TLS assertion
# off or change the Kafka client's transport, in any spelling (case, '.', '-', '_', indexed _0) of
# the key, nor through a JVM option. fbx.guard (_fbx_helpers.tpl, cicd-templates 73e1881) plus
# _helpers.tpl loan.guardValues. A dotted key is escaped (\.) so --set-string sets one literal key, not a nested map; values
# stay plain (no '{' or ',') so a refusal comes from the guard, never from helm's --set parser.
for forbidden in "config.SPRING_CONFIG_IMPORT=optional:configtree:/etc/fintechbankx/config/" \
                 "config.spring_config_location=file:/etc/other/" \
                 "config.Spring_Config_Additional_Location=file:/etc/other/" \
                 "config.SPRING_CONFIG_IMPORT_0=file:/etc/other/" \
                 "config.SPRING_CONFIG_LOCATION_1_=file:/etc/other/" \
                 "config.spring\.config\.import=file:/etc/other/" \
                 "config.spring-config-import=file:/etc/other/" \
                 "config.SPRING_CONFIG_NAME=other" \
                 "config.SPRING_CONFIG_ADDITIONALLOCATION=file:/etc/other/" \
                 "config.SPRING_DATASOURCE_URL=jdbc:postgresql://other:5432/db" \
                 "config.spring\.datasource\.url=jdbc:postgresql://other:5432/db" \
                 "config.SPRING_DATASOURCE_HIKARI_JDBC_URL=jdbc:postgresql://other:5432/db" \
                 "config.SPRING_DATASOURCE_HIKARI_JDBCURL=jdbc:postgresql://other:5432/db" \
                 "config.spring\.datasource\.hikari\.data-source-properties\.sslmode=disable" \
                 "config.SPRING_FLYWAY_URL=jdbc:postgresql://other:5432/db" \
                 "config.SPRING_FLYWAY_USER=other" \
                 "config.SPRING_LIQUIBASE_URL=jdbc:postgresql://other:5432/db" \
                 "config.SPRING_R2DBC_URL=r2dbc:postgresql://other:5432/db" \
                 "config.SPRING_APPLICATION_JSON=x" \
                 "config.spring\.application\.json=x" \
                 "config.SPRINGAPPLICATIONJSON=x" \
                 "config.MY_JDBC_URL=jdbc:postgresql://other:5432/db" \
                 "config.PG_SSLFACTORY=org.postgresql.ssl.NonValidatingFactory" \
                 "config.PG_SSLHOSTNAMEVERIFIER=x.Y" \
                 "config.FINTECHBANKX_TLS_ENFORCE=false" \
                 "config.fintechbankx\.tls\.enforce=false" \
                 "config.fintechbankx-tls-enforce=false" \
                 "config.FINTECHBANKX_TLS_ENFORCE_0=false" \
                 "config.FINTECHBANKXTLS_ENFORCE=false" \
                 "config.SPRING_PROFILES_ACTIVE=kafka-msk\,local" \
                 "config.SPRING_PROFILES_ACTIVE=kafka-msk" \
                 "config.SPRING_PROFILES_ACTIVE_0=local" \
                 "config.spring\.profiles\.active=local" \
                 "config.spring-profiles-active=local" \
                 "config.SPRING_PROFILES_INCLUDE=local" \
                 "config.SPRING_PROFILES_INCLUDE_0_=local" \
                 "config.SPRING_PROFILES_DEFAULT=local" \
                 "config.SPRING_PROFILES_GROUP_KAFKA_MSK=local" \
                 "config.spring\.profiles\.group\.kafka-msk=local" \
                 "config.SPRING_PROFILES_GROUP_KAFKA_MSK_0=local" \
                 "config.SPRING_KAFKA_SECURITY_PROTOCOL=PLAINTEXT" \
                 "config.SPRING_KAFKA_PRODUCER_SECURITY_PROTOCOL=PLAINTEXT" \
                 "config.SPRING_KAFKA_CONSUMER_SECURITY_PROTOCOL=PLAINTEXT" \
                 "config.SPRING_KAFKA_PROPERTIES_SECURITY_PROTOCOL=PLAINTEXT" \
                 "config.spring\.kafka\.producer\.properties\.security\.protocol=PLAINTEXT" \
                 "config.SPRING_KAFKA_PROPERTIES_SASL_JAAS_CONFIG=x" \
                 "config.SPRING_KAFKA_PROPERTIES_SSL_ENDPOINT_IDENTIFICATION_ALGORITHM=" \
                 "config.KAFKA_SECURITY_PROTOCOL=PLAINTEXT" \
                 "config.KAFKA_SECURITY_PROTOCOL=SASL_PLAINTEXT" \
                 "config.JAVA_TOOL_OPTIONS=-Dfintechbankx.tls.enforce=false" \
                 "config.JAVA_TOOL_OPTIONS=-Dspring.profiles.active=local" \
                 "config.JAVA_TOOL_OPTIONS=-Dspring.datasource.url=jdbc:postgresql://other:5432/db" \
                 "config.JAVA_TOOL_OPTIONS=-Dspring.kafka.security.protocol=PLAINTEXT" \
                 "config.JDK_JAVA_OPTIONS=-Dfintechbankx.tls.enforce=false" \
                 "config.JDK_JAVA_OPTIONS=-Dspring.config.import=file:/etc/other/" \
                 "config._JAVA_OPTIONS=-Dfintechbankx.tls.enforce=false" \
                 "config._JAVA_OPTIONS=-Djavax.net.ssl.trustStore=/tmp/x" \
                 "config._JAVA_OPTIONS=@/tmp/opts" \
                 "config.java_tool_options=-XX:VMOptionsFile=/tmp/opts" \
                 "config.JAVA_TOOL_OPTIONS=-XX:Flags=/tmp/flags" \
                 "kafka.profile=local" \
                 "kafka.profile=kafka-msk\,local" \
                 "kafka.profile=" \
                 "kafka.profile=KAFKA-MSK"; do
  if helm template ci "$chart" $args --set-string "$forbidden" > /dev/null 2>&1; then
    echo "chart rendered with $forbidden" >&2
    exit 1
  fi
done
# The guard leaves the settings the chart needs alone: the two Kafka profiles, the credential
# names the reference guard allows, and a JVM option that sets neither TLS nor configuration.
grep -q 'SPRING_PROFILES_ACTIVE: "kafka-msk"' <<<"$rendered"
helm template ci "$chart" $args --set kafka.profile=kafka-strimzi --set-string config.KAFKA_SECURITY_PROTOCOL=SSL | grep -q 'SPRING_PROFILES_ACTIVE: "kafka-strimzi"'
# What the vendored guard covers beyond 2caa48f, each refused for its own reason: SSL bundle names, DB_SSL_ROOT_CERT
# and other TLS parameter names, -Djdk.tls.* / -Djava.security.properties / hostname verification in a
# JVM option, '$(' and '${' references, envFrom, DB_URL in another spelling, every spring.config.* name,
# and the Kafka protocol matched to the runtime of kafka.profile; plus this chart's own rules.
refuse 'config.ACCOUNTS_SERVICE_BASE_URL is required' --set-string config.ACCOUNTS_SERVICE_BASE_URL=
refuse 'config.SPRING_SSL_BUNDLE_PEM_DB_TRUSTSTORE_CERTIFICATE is not allowed' --set-string config.SPRING_SSL_BUNDLE_PEM_DB_TRUSTSTORE_CERTIFICATE=x
refuse 'config.SPRING_KAFKA_SSL_BUNDLE is not allowed' --set-string config.SPRING_KAFKA_SSL_BUNDLE=other
refuse 'config.spring.ssl.bundle.jks.db.truststore.location is not allowed' --set-string 'config.spring\.ssl\.bundle\.jks\.db\.truststore\.location=/tmp/x'
refuse 'config.DB_SSL_ROOT_CERT is not allowed' --set-string config.DB_SSL_ROOT_CERT=
refuse 'config.PGSSLROOTCERT is not allowed' --set-string config.PGSSLROOTCERT=/tmp/x
refuse 'config.OPF_DB_SSL_MODE is not allowed' --set-string config.OPF_DB_SSL_MODE=disable
refuse 'config.JAVA_TOOL_OPTIONS must not mention' --set-string 'config.JAVA_TOOL_OPTIONS=-Djdk.tls.client.protocols=TLSv1'
refuse 'config.JDK_JAVA_OPTIONS must not mention' --set-string 'config.JDK_JAVA_OPTIONS=-Djava.security.properties=/tmp/java.security'
refuse 'config._JAVA_OPTIONS must not mention' --set-string 'config._JAVA_OPTIONS=-Djdk.internal.httpclient.disableHostnameVerification=true'
refuse 'config.JAVA_TOOL_OPTIONS must not mention' --set-string 'config.JAVA_TOOL_OPTIONS=-Dspring..config.import=file:/etc/other/'
refuse 'config.JAVA_TOOL_OPTIONS must not contain' --set-string 'config.JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75 $(FBX_X)'
refuse 'config.JAVA_TOOL_OPTIONS must not mention .*kafka' --set-string 'config.JAVA_TOOL_OPTIONS=-Dspring.kafka.bootstrap-servers=other:9092'
refuse 'extraEnv.FOO must not contain' --set-string 'extraEnv[0].name=FOO' --set-string 'extraEnv[0].value=$(DB_URL)'
refuse 'extraEnv must not set DB_URL' --set-string 'extraEnv[0].name=DB_URL' --set-string 'extraEnv[0].value=jdbc:postgresql://other:5432/db'
refuse 'extraEnv must not set DB_SSL_ROOT_CERT' --set-string 'extraEnv[0].name=DB_SSL_ROOT_CERT' --set-string 'extraEnv[0].value='
refuse 'extraEnv must not set SPRING_PROFILES_ACTIVE' --set-string 'extraEnv[0].name=SPRING_PROFILES_ACTIVE' --set-string 'extraEnv[0].valueFrom.configMapKeyRef.name=other' --set-string 'extraEnv[0].valueFrom.configMapKeyRef.key=p'
refuse 'envFrom is not supported' --set-string 'envFrom[0].configMapRef.name=other'
refuse 'extraEnvFrom is not supported' --set-string 'extraEnvFrom[0].secretRef.name=other'
refuse 'config.DB_URL must not contain' --set-string "config.DB_URL=jdbc:postgresql://ci-db:5432/db?sslmode=verify-full&sslrootcert=$ca&options=\${X}"
refuse 'config.DB_URL must not contain' --set-string "config.DB_URL=jdbc:postgresql://ci-db:5432/db?sslmode=verify-full&sslrootcert=$ca&options=\$(X)"
refuse 'config.db.url is DB_URL in another spelling' --set-string 'config.db\.url=jdbc:postgresql://other:5432/db'
refuse 'config.dbUrl is DB_URL in another spelling' --set-string 'config.dbUrl=jdbc:postgresql://other:5432/db'
refuse 'config.spring.config.activate.on-profile is not allowed' --set-string 'config.spring\.config\.activate\.on-profile=local'
refuse 'config.spring.pro-files.active is not allowed' --set-string 'config.spring\.pro-files\.active=local'
refuse 'must be https' --set-string 'config.spring\.kafka\.producer\.properties\.ssl\.endpoint\.identification\.algorithm=http'
refuse 'must be SSL with kafka.runtime strimzi' --set kafka.profile=kafka-strimzi
refuse 'must be SASL_SSL with kafka.runtime msk' --set-string config.KAFKA_SECURITY_PROTOCOL=SSL
refuse 'config.SPRING_KAFKA_PRODUCER_SECURITY_PROTOCOL must be SASL_SSL or SSL' --set-string config.SPRING_KAFKA_PRODUCER_SECURITY_PROTOCOL=SASL_PLAINTEXT
refuse 'config.SPRING_KAFKA_PROPERTIES_SASL_JAAS_CONFIG is not allowed' --set-string config.SPRING_KAFKA_PROPERTIES_SASL_JAAS_CONFIG=x
refuse 'kafka.profile must be kafka-msk or kafka-strimzi' --set kafka.profile=local
refuse 'kafka.profile is required' --set kafka.profile=null
# Round 7 (cicd-templates 6b6c317), the platform suites' negative cases through this chart's value names
# (the chart renders no extraEnv, envFrom or javaToolOptions; extraEnv is mapped into the guard, so it is
# probed with --set). Database CA mount: pinned while the bundle is mounted, which is always.
refuse 'databaseCa.mountPath must be /etc/fintechbankx/rds-ca \(got "/app/config"\)' --set-string databaseCa.mountPath=/app/config --set-string "config.DB_URL=jdbc:postgresql://ci-db:5432/db?sslmode=verify-full&sslrootcert=/app/config/global-bundle.pem"
refuse 'databaseCa.mountPath must be /etc/fintechbankx/rds-ca' --set-string databaseCa.mountPath=/etc/fintechbankx/rds-ca/
refuse 'databaseCa.key must be global-bundle.pem \(got "other-bundle.pem"\)' --set-string databaseCa.key=other-bundle.pem --set-string "config.DB_URL=jdbc:postgresql://ci-db:5432/db?sslmode=verify-full&sslrootcert=/etc/fintechbankx/rds-ca/other-bundle.pem"
refuse 'databaseCa.configMapName must be rds-ca-bundle \(got "any-config"\)' --set-string databaseCa.configMapName=any-config
refuse 'databaseCa.configMapName is required while databaseCa.enabled' --set databaseCa.configMapName=null
# Database names and URLs the suites add to the round 6 list.
refuse 'config.DB_URL must use sslmode=verify-full' --set-string 'config.DB_URL=jdbc:postgresql://ci-db:5432/db?xsslmode=verify-full&sslmode=disable&sslrootcert=/etc/fintechbankx/rds-ca/global-bundle.pem'
refuse 'config.DB_URL must set sslrootcert=/etc/fintechbankx/rds-ca/global-bundle.pem exactly once' --set-string 'config.DB_URL=jdbc:postgresql://ci-db:5432/db?sslmode=verify-full'
refuse 'config.DB_URL must not set sslfactory' --set-string "config.DB_URL=jdbc:postgresql://ci-db:5432/db?sslmode=verify-full&sslrootcert=$ca&sslfactory=org.postgresql.ssl.NonValidatingFactory"
refuse 'config.DB_URL must not set service' --set-string "config.DB_URL=jdbc:postgresql://ci-db:5432/db?sslmode=verify-full&sslrootcert=$ca&service=loan"
refuse 'config.DB_URL must not percent-encode' --set-string "config.DB_URL=jdbc:postgresql://ci-db:5432/db?sslmode=verify-full&sslrootcert=$ca&sslmode%3Ddisable"
refuse 'config.DB_URL has a query parameter name that is not plain' --set-string "config.DB_URL=jdbc:postgresql://ci-db:5432/db?sslmode=verify-full&sslrootcert=$ca&ssl%6Dode=disable"
refuse 'config.DB_URL must spell SSLMODE in lower case' --set-string "config.DB_URL=jdbc:postgresql://ci-db:5432/db?SSLMODE=verify-full&sslrootcert=$ca"
refuse 'config.DB_URL must carry TLS parameters only in the query string' --set-string "config.DB_URL=jdbc:postgresql://ci-db:5432/db;sslmode=disable?sslmode=verify-full&sslrootcert=$ca"
refuse 'config.DB_URL must be a jdbc:' --set-string 'config.DB_URL=postgresql://ci-db:5432/db?sslmode=disable'
refuse 'config.SPRING_DATASOURCE_URL is not allowed' --set-string 'config.SPRING_DATASOURCE_URL=jdbc:postgresql://ci-db:5432/db?sslmode=require'
refuse 'config.SPRING_FLYWAY_URL is not allowed' --set-string "config.SPRING_FLYWAY_URL=jdbc:postgresql://ci-db:5432/db?sslmode=verify-full&sslrootcert=$ca"
refuse 'config.SPRING_DATASOURCE_HIKARI_JDBC_URL is not allowed' --set-string 'config.SPRING_DATASOURCE_HIKARI_JDBC_URL=x'
refuse 'extraEnv must not set DB_URL' --set-string 'extraEnv[0].name=DB_URL' --set-string 'extraEnv[0].value=jdbc:postgresql://ci-db:5432/db?sslmode=require'
refuse 'extraEnv must not set SPRING_DATASOURCE_URL' --set-string 'extraEnv[0].name=SPRING_DATASOURCE_URL' --set-string 'extraEnv[0].value=jdbc:postgresql://ci-db:5432/db?sslmode=require'
refuse 'extraEnv must not set Spring_Datasource_Hikari_Jdbc_Url' --set-string 'extraEnv[0].name=Spring_Datasource_Hikari_Jdbc_Url' --set-string "extraEnv[0].value=jdbc:postgresql://ci-db:5432/db?sslmode=verify-full&sslrootcert=$ca"
refuse 'extraEnv must not set SPRING_FLYWAY_URL' --set-string 'extraEnv[0].name=SPRING_FLYWAY_URL' --set-string "extraEnv[0].value=jdbc:postgresql://ci-db:5432/db?sslmode=verify-full&sslrootcert=$ca"
refuse 'extraEnv must not set spring_application_json' --set-string 'extraEnv[0].name=spring_application_json' --set-string 'extraEnv[0].value=x'
refuse 'extraEnv.REPORTING_DB_JDBC must use sslmode=verify-full' --set-string 'extraEnv[0].name=REPORTING_DB_JDBC' --set-string 'extraEnv[0].value=jdbc:postgresql://ci-db:5432/db?sslmode=require'
refuse 'config.DB_URL must use sslmode=verify-full' --set-string 'config.DB_URL=jdbc:aws-wrapper:postgresql://ci-db:5432/db?sslmode=require'
# Kafka client TLS names: a client TLS setting, in any spelling (the trust store, key store and protocol
# come from the kafka.profile), on config and extraEnv; only the ssl.endpoint.identification.algorithm
# value is checked (https).
refuse 'config.SPRING_KAFKA_SSL_TRUSTSTORE_LOCATION is not allowed: a Kafka client TLS setting' --set-string config.SPRING_KAFKA_SSL_TRUSTSTORE_LOCATION=file:/etc/other/any.jks
refuse 'config.SPRING_KAFKA_SSL_TRUST_STORE_LOCATION is not allowed: a Kafka client TLS setting' --set-string config.SPRING_KAFKA_SSL_TRUST_STORE_LOCATION=file:/etc/other/any.jks
refuse 'config.spring.kafka.ssl.trust-store-certificates is not allowed: a Kafka client TLS setting' --set-string 'config.spring\.kafka\.ssl\.trust-store-certificates=-----BEGIN CERTIFICATE-----'
refuse 'config.spring.kafka.s-sl.trust-store-location is not allowed: a Kafka client TLS setting' --set-string 'config.spring\.kafka\.s-sl\.trust-store-location=file:/etc/other/any.jks'
refuse 'config.SPRING_KAFKA_SSL_PROTOCOL is not allowed: a Kafka client TLS setting' --set-string config.SPRING_KAFKA_SSL_PROTOCOL=TLSv1
refuse 'extraEnv must not set SPRING_KAFKA_PRODUCER_SSL_KEY_STORE_LOCATION \(value or valueFrom\): a Kafka client TLS setting' --set-string 'extraEnv[0].name=SPRING_KAFKA_PRODUCER_SSL_KEY_STORE_LOCATION' --set-string 'extraEnv[0].value=file:/etc/other/any.p12'
refuse 'extraEnv must not set spring.kafka.consumer.ssl.trust-store-type \(value or valueFrom\): a Kafka client TLS setting' --set-string 'extraEnv[0].name=spring.kafka.consumer.ssl.trust-store-type' --set-string 'extraEnv[0].valueFrom.configMapKeyRef.name=other' --set-string 'extraEnv[0].valueFrom.configMapKeyRef.key=type'
refuse 'config.SPRING_KAFKA_ADMIN_SSL_TRUST_STORE_CERTIFICATES is not allowed: a Kafka client TLS setting' --set-string 'config.SPRING_KAFKA_ADMIN_SSL_TRUST_STORE_CERTIFICATES=-----BEGIN CERTIFICATE-----'
refuse 'config.spring.kafka.streams.ssl.key-password is not allowed: a Kafka client TLS setting' --set-string 'config.spring\.kafka\.streams\.ssl\.key-password=x'
refuse 'config.spring.kafka.properties.ssl.truststore.location is not allowed: a Kafka client TLS setting' --set-string 'config.spring\.kafka\.properties\.ssl\.truststore\.location=/etc/other/any.jks'
refuse 'config.SPRING_KAFKA_PROPERTIES_SSL_TRUSTSTORE_LOCATION is not allowed: a Kafka client TLS setting' --set-string config.SPRING_KAFKA_PROPERTIES_SSL_TRUSTSTORE_LOCATION=/etc/other/any.jks
refuse 'config.spring.kafka.pro-perties.ssl.truststore.location is not allowed: a Kafka client TLS setting' --set-string 'config.spring\.kafka\.pro-perties\.ssl\.truststore\.location=/etc/other/any.jks'
refuse 'config.spring.kafka.streams.properties.ssl.keystore.location is not allowed: a Kafka client TLS setting' --set-string 'config.spring\.kafka\.streams\.properties\.ssl\.keystore\.location=/etc/other/any.p12'
refuse 'extraEnv must not set SPRING_KAFKA_CONSUMER_PROPERTIES_SSL_TRUSTSTORE_TYPE \(value or valueFrom\): a Kafka client TLS setting' --set-string 'extraEnv[0].name=SPRING_KAFKA_CONSUMER_PROPERTIES_SSL_TRUSTSTORE_TYPE' --set-string 'extraEnv[0].valueFrom.secretKeyRef.name=other' --set-string 'extraEnv[0].valueFrom.secretKeyRef.key=type'
refuse 'config.SPRING_KAFKA_PROPERTIES_SSL_ENDPOINT_IDENTIFICATION_ALGORITHM must be https' --set-string config.SPRING_KAFKA_PROPERTIES_SSL_ENDPOINT_IDENTIFICATION_ALGORITHM=
refuse 'config.JDK_JAVA_OPTIONS must not mention .*kafka' --set-string 'config.JDK_JAVA_OPTIONS=-Dspring.Kaf_ka.producer.acks=0'
refuse 'extraEnv._JAVA_OPTIONS must not mention .*kafka' --set-string 'extraEnv[0].name=_JAVA_OPTIONS' --set-string 'extraEnv[0].value=-Dlogging.level.org.apache.kafka=DEBUG'
refuse 'config.JAVA_TOOL_OPTIONS must not mention .*kafka' --set-string 'config.JAVA_TOOL_OPTIONS=-Dspring.kafka.ssl.trust-store-location=file:/etc/other/any.jks'
# Strimzi: the client certificate, key and CA come from a Secret (extraEnv valueFrom.secretKeyRef), never a literal or config.
refuse 'extraEnv KAFKA_TLS_CA must come from valueFrom.secretKeyRef' --set kafka.profile=kafka-strimzi --set-string config.KAFKA_SECURITY_PROTOCOL=SSL --set-string 'extraEnv[0].name=KAFKA_TLS_CA' --set-string 'extraEnv[0].value=-----BEGIN CERTIFICATE-----'
refuse 'extraEnv KAFKA_TLS_CERT must come from valueFrom.secretKeyRef' --set kafka.profile=kafka-strimzi --set-string config.KAFKA_SECURITY_PROTOCOL=SSL --set-string 'extraEnv[0].name=KAFKA_TLS_CERT' --set-string 'extraEnv[0].valueFrom.configMapKeyRef.name=other' --set-string 'extraEnv[0].valueFrom.configMapKeyRef.key=user.crt'
refuse 'extraEnv KAFKA_TLS_KEY must come from valueFrom.secretKeyRef' --set kafka.profile=kafka-strimzi --set-string config.KAFKA_SECURITY_PROTOCOL=SSL --set-string 'extraEnv[0].name=KAFKA_TLS_KEY' --set-string 'extraEnv[0].valueFrom.fieldRef.fieldPath=metadata.name'
refuse 'config.KAFKA_TLS_CA is not allowed: .*Secret' --set kafka.profile=kafka-strimzi --set-string config.KAFKA_SECURITY_PROTOCOL=SSL --set-string 'config.KAFKA_TLS_CA=-----BEGIN CERTIFICATE-----'
refuse 'config.KAFKA_TLS_KEY is not allowed: .*Secret' --set-string config.KAFKA_TLS_KEY=x
refuse 'extraEnv kafka.tls.ca is not allowed: .*KAFKA_TLS_CA' --set kafka.profile=kafka-strimzi --set-string config.KAFKA_SECURITY_PROTOCOL=SSL --set-string 'extraEnv[0].name=kafka.tls.ca' --set-string 'extraEnv[0].valueFrom.secretKeyRef.name=kafka-client-tls' --set-string 'extraEnv[0].valueFrom.secretKeyRef.key=ca.crt'
refuse 'config.Kafka_Tls_Cert is not allowed' --set-string config.Kafka_Tls_Cert=x
refuse 'config.SPRING_KAFKA_SSL_TRUST_STORE_CERTIFICATES is not allowed: a Kafka client TLS setting' --set kafka.profile=kafka-strimzi --set-string config.KAFKA_SECURITY_PROTOCOL=SSL --set-string 'config.SPRING_KAFKA_SSL_TRUST_STORE_CERTIFICATES=-----BEGIN CERTIFICATE-----'
# DocumentDB: this service has no DocumentDB; the connection string and the spring.data.mongodb.* settings are still refused.
refuse 'config.SPRING_DATA_MONGODB_URI is not allowed: a spring.data.mongodb.\* property' --set-string 'config.SPRING_DATA_MONGODB_URI=mongodb://docdb.example.internal:27017/db?tls=false'
refuse 'config.spring.data.mongodb.uri is not allowed: a spring.data.mongodb.\* property' --set-string 'config.spring\.data\.mongodb\.uri=mongodb://docdb.example.internal:27017/db?tls=false'
refuse 'config.spring.data.mongo-db.uri is not allowed: a spring.data.mongodb.\* property' --set-string 'config.spring\.data\.mongo-db\.uri=mongodb://docdb.example.internal:27017/db?tls=false'
refuse 'config.spring.data\[mongodb\].uri is not allowed: a spring.data.mongodb.\* property' --set-string 'config.spring\.data\[mongodb\]\.uri=mongodb://docdb.example.internal:27017/db?tls=false'
refuse 'config.SPRING_DATA_MONGODB_SSL_ENABLED is not allowed: a spring.data.mongodb.\* property' --set-string config.SPRING_DATA_MONGODB_SSL_ENABLED=false
refuse 'extraEnv must not set spring.data.mongodb.host \(value or valueFrom\): a spring.data.mongodb.\* property' --set-string 'extraEnv[0].name=spring.data.mongodb.host' --set-string 'extraEnv[0].value=other.example.internal'
refuse 'extraEnv must not set SPRING_DATA_MONGODB_REPLICA_SET_NAME \(value or valueFrom\): a spring.data.mongodb.\* property' --set-string 'extraEnv[0].name=SPRING_DATA_MONGODB_REPLICA_SET_NAME' --set-string 'extraEnv[0].valueFrom.configMapKeyRef.name=other' --set-string 'extraEnv[0].valueFrom.configMapKeyRef.key=rs'
refuse 'config.spring.data.mongodb.password is not allowed: a spring.data.mongodb.\* property' --set-string 'config.spring\.data\.mongodb\.password=x'
refuse 'config.MONGODB_URI is not allowed: MONGODB_URI is the DocumentDB connection string' --set-string 'config.MONGODB_URI=mongodb://docdb.example.internal:27017/db?tls=false'
refuse 'extraEnv MONGODB_URI must come from valueFrom.secretKeyRef' --set-string 'extraEnv[0].name=MONGODB_URI' --set-string 'extraEnv[0].value=mongodb://docdb.example.internal:27017/db?tlsAllowInvalidCertificates=true'
refuse 'extraEnv MONGODB_URI must come from valueFrom.secretKeyRef' --set-string 'extraEnv[0].name=MONGODB_URI' --set-string 'extraEnv[0].valueFrom.configMapKeyRef.name=other' --set-string 'extraEnv[0].valueFrom.configMapKeyRef.key=uri'
refuse 'extraEnv mongodb.uri is not allowed: MONGODB_URI is the DocumentDB connection string' --set-string 'extraEnv[0].name=mongodb.uri' --set-string 'extraEnv[0].valueFrom.secretKeyRef.name=docdb-app' --set-string 'extraEnv[0].valueFrom.secretKeyRef.key=uri'
refuse 'config.JAVA_TOOL_OPTIONS must not mention .*mongodb' --set-string 'config.JAVA_TOOL_OPTIONS=-DMONGODB_URI=mongodb://other.example.internal:27017/db'
refuse 'config.JAVA_TOOL_OPTIONS must not mention .*mongodb' --set-string 'config.JAVA_TOOL_OPTIONS=-Dspring.data.mongo-db.uri=mongodb://other.example.internal:27017/db'
# Property-reading names: Spring's property mapper drops '_' inside an element, so these bind the same
# spring.kafka.ssl.*, spring.data.mongodb.*, spring.profiles.*, spring.config.*, spring.application.json,
# spring.ssl.*, fintechbankx.tls.* and spring.datasource.* / spring.flyway.* properties.
refuse 'config.spring.kafka.s_sl.trust-store-type is not allowed: a Kafka client TLS setting' --set kafka.profile=kafka-strimzi --set-string config.KAFKA_SECURITY_PROTOCOL=SSL --set-string 'config.spring\.kafka\.s_sl\.trust-store-type=PKCS12'
refuse 'extraEnv must not set spring.kafka.consumer.s_sl.trust-store-type \(value or valueFrom\): a Kafka client TLS setting' --set-string 'extraEnv[0].name=spring.kafka.consumer.s_sl.trust-store-type' --set-string 'extraEnv[0].valueFrom.secretKeyRef.name=other' --set-string 'extraEnv[0].valueFrom.secretKeyRef.key=type'
refuse 'config.spring.kafka.con_sumer.ssl.trust-store-type is not allowed: a Kafka client TLS setting' --set-string 'config.spring\.kafka\.con_sumer\.ssl\.trust-store-type=PKCS12'
refuse 'config.spring.kafka.streams.pro_perties.ssl.truststore.location is not allowed: a Kafka client TLS setting' --set-string 'config.spring\.kafka\.streams\.pro_perties\.ssl\.truststore\.location=/etc/other/any.jks'
refuse 'config.spring.kafka.security.pro_tocol must be SASL_SSL or SSL' --set-string 'config.spring\.kafka\.security\.pro_tocol=PLAINTEXT'
refuse 'extraEnv spring.kafka.secu_rity.protocol must set a literal value' --set-string 'extraEnv[0].name=spring.kafka.secu_rity.protocol' --set-string 'extraEnv[0].valueFrom.configMapKeyRef.name=other' --set-string 'extraEnv[0].valueFrom.configMapKeyRef.key=protocol'
refuse 'config.spring.data.mongo_db.host is not allowed: a spring.data.mongodb.\* property' --set-string 'config.spring\.data\.mongo_db\.host=placeholder-host'
refuse 'extraEnv must not set spring.data.mongo_db.uri \(value or valueFrom\): a spring.data.mongodb.\* property' --set-string 'extraEnv[0].name=spring.data.mongo_db.uri' --set-string 'extraEnv[0].valueFrom.secretKeyRef.name=other' --set-string 'extraEnv[0].valueFrom.secretKeyRef.key=uri'
refuse 'config.spring.da_ta.mongodb.port is not allowed: a spring.data.mongodb.\* property' --set-string 'config.spring\.da_ta\.mongodb\.port=27018'
refuse 'config.spring.pro_files.active is not allowed: a profile can activate' --set-string 'config.spring\.pro_files\.active=local'
refuse 'extraEnv must not set spring.pro_files.default \(value or valueFrom\): a profile can activate' --set-string 'extraEnv[0].name=spring.pro_files.default' --set-string 'extraEnv[0].value=local'
refuse 'extraEnv must not set s_pring.profiles.active \(value or valueFrom\): a profile can activate' --set-string 'extraEnv[0].name=s_pring.profiles.active' --set-string 'extraEnv[0].value=local'
refuse 'config.spring.con_fig.import is not allowed: a spring.config.\* property' --set-string 'config.spring\.con_fig\.import=optional:file:/etc/other/override.yml'
refuse 'extraEnv must not set spring.applica_tion.json \(value or valueFrom\): it can redirect or override the datasource' --set-string 'extraEnv[0].name=spring.applica_tion.json' --set-string 'extraEnv[0].value=x'
refuse 'config.spring.s_sl.bundle.pem.documentdb.truststore.certificate is not allowed: an SSL bundle property' --set-string 'config.spring\.s_sl\.bundle\.pem\.documentdb\.truststore\.certificate=file:/etc/other/any-ca.pem'
refuse 'config.fintech_bankx.tls.enforce is not allowed: it can switch off the service' --set-string 'config.fintech_bankx\.tls\.enforce=false'
refuse 'extraEnv must not set fintechbankx.t_ls.enforce \(value or valueFrom\): it can switch off' --set-string 'extraEnv[0].name=fintechbankx.t_ls.enforce' --set-string 'extraEnv[0].valueFrom.configMapKeyRef.name=other' --set-string 'extraEnv[0].valueFrom.configMapKeyRef.key=enforce'
refuse 'config.spring.data_source.url is not allowed: it can redirect or override the datasource' --set-string 'config.spring\.data_source\.url=jdbc:h2:mem:x'
refuse 'config.spring.data_source.driver-class-name is not allowed: it can redirect or override the datasource' --set-string 'config.spring\.data_source\.driver-class-name=org.h2.Driver'
refuse 'config.spring.fly_way.url is not allowed: it can redirect or override the datasource' --set-string 'config.spring\.fly_way\.url=jdbc:h2:mem:x'
# Controls: names that only look alike, and the credential names, still render.
helm template ci "$chart" $args --set-string 'config.app\.feature_flags\.new_ui=true' --set-string 'config.logging\.level\.com_example_loan=DEBUG' > /dev/null
helm template ci "$chart" $args --set-string config.SPRING_DATA_MONGODB_USERNAME=of_app --set-string 'extraEnv[0].name=spring_data_mongodb_username' --set-string 'extraEnv[0].value=of_app' > /dev/null
helm template ci "$chart" $args --set-string config.SPRING_DATASOURCE_USERNAME=loan_lifecycle_app > /dev/null
helm template ci "$chart" $args --set-string config.JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError" > /dev/null
# Flyway runs only in the hook Job, as the schema owner; the pods never get that secret.
job="$(helm template ci "$chart" $args --show-only templates/migration-job.yaml)"
grep -q 'helm.sh/hook: pre-install,pre-upgrade' <<<"$job"
grep -q 'args: \["migrate"\]' <<<"$job"
grep -q 'key: "dev/loan-lifecycle-service/db-migration"' <<<"$job"
deployment="$(helm template ci "$chart" $args --show-only templates/deployment.yaml)"
grep -A1 'name: SPRING_FLYWAY_ENABLED' <<<"$deployment" | grep -q 'value: "false"'
if grep -q 'db-migration' <<<"$deployment"; then echo "the service pods reference the migration secret" >&2; exit 1; fi
if helm template ci "$chart" $args --set migration.remoteSecretName= > /dev/null 2>&1; then
  echo "chart rendered without the migration secret" >&2
  exit 1
fi
# The Job pod carries app.kubernetes.io/name=<SA> (mesh Aurora egress) and component
# db-migration, API pods component service (cicd-templates 335a345); no Service, PDB,
# Deployment, topology spread or NetworkPolicy selector may select the Job pod, and
# only API pods carry fintechbankx.io/service-id.
helm template ci "$chart" $args --set networkPolicy.enabled=true | python3 scripts/ci/helm-selector-check.py loan-lifecycle-service svc-ln-loan-lifecycle
if helm template ci "$chart" $args --set networkPolicy.enabled=true \
     | sed 's/app.kubernetes.io\/component: db-migration/app.kubernetes.io\/component: service/' \
     | python3 scripts/ci/helm-selector-check.py loan-lifecycle-service svc-ln-loan-lifecycle 2>/dev/null; then
  echo "helm-selector-check accepted a Job pod the Service would select" >&2
  exit 1
fi
# Admission policy fintechbankx-externalsecret-scope: label app.kubernetes.io/name=<SA>,
# keys only under <env>/<SA>/, no dataFrom or sourceRef (hook resources included).
helm template ci "$chart" $args | python3 scripts/ci/externalsecret-scope.py loan-lifecycle-service dev
# Secret naming contract, the check copied from customer-profile-kyc-core 58f7369:
# each ExternalSecret is labelled with the service account and reads only <env>/<SA>/ keys.
# Template interpolation: every key and value is quoted, so a value with a newline stays one value and a
# key with a newline is refused (fbx.validateKeyNames) instead of opening a second key.
tmp="$(mktemp -d)"
printf 'config:\n  "OIDC_AUDIENCE\\nSPRING_CONFIG_IMPORT": "x"\n' > "$tmp/key-newline.yaml"
refuse 'is not a ConfigMap key' -f "$tmp/key-newline.yaml"
# Schema-management names, JVM options and bracket names (cicd-templates 73e1881): each refused for its own reason.
refuse 'config\.SPRING_JPA_HIBERNATE_DDL_AUTO is not allowed: a schema-management setting' --set-string config.SPRING_JPA_HIBERNATE_DDL_AUTO=create-drop
refuse 'config\.spring\.jpa\.generate-ddl is not allowed: a schema-management setting' --set-string 'config.spring\.jpa\.generate-ddl=true'
refuse 'config\.SPRING_JPA_PROPERTIES_HIBERNATE_HBM2DDL_AUTO is not allowed: a schema-management setting' --set-string config.SPRING_JPA_PROPERTIES_HIBERNATE_HBM2DDL_AUTO=update
refuse 'extraEnv must not set SPRING_SQL_INIT_MODE \(value or valueFrom\): a schema-management setting' --set-json 'extraEnv=[{"name":"SPRING_SQL_INIT_MODE","value":"always"}]'
refuse 'extraEnv must not set spring\.sql\.init\.schema-locations \(value or valueFrom\): a schema-management setting' --set-json 'extraEnv=[{"name":"spring.sql.init.schema-locations","valueFrom":{"configMapKeyRef":{"name":"other","key":"schema"}}}]'
refuse 'config\.SPRING_SQLINIT_MODE is not allowed: a schema-management setting' --set-string config.SPRING_SQLINIT_MODE=always
refuse 'config\.JAVAX_PERSISTENCE_SCHEMA_GENERATION_DATABASE_ACTION is not allowed: a schema-management setting' --set-string config.JAVAX_PERSISTENCE_SCHEMA_GENERATION_DATABASE_ACTION=drop-and-create
refuse 'config\.spring\.j_pa\.hibernate\.ddl-auto is not allowed: a schema-management setting' --set-string 'config.spring\.j_pa\.hibernate\.ddl-auto=create'
refuse 'extraEnv must not set spring\[jpa\]\.generate-ddl .*: a schema-management setting' --set-json 'extraEnv=[{"name":"spring[jpa].generate-ddl","value":"true"}]'
refuse 'extraEnv must not set _SPRING_JPA_DEFER_DATASOURCE_INITIALIZATION .*: a schema-management setting' --set-json 'extraEnv=[{"name":"_SPRING_JPA_DEFER_DATASOURCE_INITIALIZATION","value":"true"}]'
refuse 'config\.JAVA_TOOL_OPTIONS must not mention .*spring\.jpa, spring\.sql\.init, hibernate' --set-string 'config.JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75 -Dspring.jpa.hibernate.ddl-auto=create-drop'
refuse 'config\.JDK_JAVA_OPTIONS must not mention .*hibernate' --set-string config.JDK_JAVA_OPTIONS=-Dhibernate.hbm2ddl.auto=update
refuse 'extraEnv\.JAVA_TOOL_OPTIONS must not mention .*jakarta\.persistence' --set-json 'extraEnv=[{"name":"JAVA_TOOL_OPTIONS","value":"-Djakarta.persistence.schema-generation.database.action=drop-and-create"}]'
refuse 'extraEnv\._JAVA_OPTIONS must not mention .*javax\.persistence' --set-json 'extraEnv=[{"name":"_JAVA_OPTIONS","value":"-Djavax.persistence.schema-generation.database.action=create"}]'
refuse 'config\.JAVA_TOOL_OPTIONS must not mention .*spring\.sql\.init' --set-string config.JAVA_TOOL_OPTIONS=-Dspring.sql.init.mode=always
refuse 'config\.JAVA_TOOL_OPTIONS must not mention .*spring\.jpa' --set-string 'config.JAVA_TOOL_OPTIONS=-Dspring.j_pa.generate-ddl=true'
refuse 'extraEnv must not set spring\.J\]PA\.generate-ddl \(value or valueFrom\): a schema-management setting' --set-json 'extraEnv=[{"name":"spring.J]PA.generate-ddl","value":"true"}]'
refuse 'extraEnv must not set -s\]pring\.sql\.init\.mode \(value or valueFrom\): a schema-management setting' --set-json 'extraEnv=[{"name":"-s]pring.sql.init.mode","value":"always"}]'
refuse 'extraEnv must not set -SPRIN\]G_JPA_HIBERNATE_DDLAUTO \(value or valueFrom\): a schema-management setting' --set-json 'extraEnv=[{"name":"-SPRIN]G_JPA_HIBERNATE_DDLAUTO","value":"create-drop"}]'
refuse 'extraEnv must not set S\]PRING\.profiles\.active \(value or valueFrom\): a profile can activate' --set-json 'extraEnv=[{"name":"S]PRING.profiles.active","value":"local"}]'
refuse 'extraEnv\.spring\.kafka\.security\.proto\]col must be SASL_SSL or SSL' --set-json 'extraEnv=[{"name":"spring.kafka.security.proto]col","value":"PLAINTEXT"}]'
printf 'config:\n  OIDC_AUDIENCE: "svc\\nSPRING_CONFIG_IMPORT: file:/etc/other/"\n  DB_USERNAME: "app\\n          args: [\\"sh\\"]"\n' > "$tmp/value-newline.yaml"
helm template ci "$chart" $args -f "$tmp/value-newline.yaml" | python3 -c '
import sys, yaml
docs = [d for d in yaml.safe_load_all(sys.stdin) if d]
cm = [d for d in docs if d["kind"] == "ConfigMap"][0]
assert "SPRING_CONFIG_IMPORT" not in cm["data"], sorted(cm["data"])
assert "\n" in cm["data"]["OIDC_AUDIENCE"], cm["data"]["OIDC_AUDIENCE"]
job = [d for d in docs if d["kind"] == "Job"][0]["spec"]["template"]["spec"]["containers"][0]
assert job["args"] == ["migrate"], job["args"]
assert "\n" in [e for e in job["env"] if e["name"] == "DB_USERNAME"][0]["value"]
print("template interpolation: a newline in a value adds no key, no arg")'
# Round-8 review: the image is one quoted scalar, so an image value cannot append container
# fields (command, args) the guard never saw. --set-file applies after the --set in $args.
printf 'ci/loan"\n          command: ["sh"]\n          args: ["-c", "x"]\n          x: "y' > "$tmp/image.txt"
helm template ci "$chart" $args --set-file "image.repository=$tmp/image.txt" 2>"$tmp/image.err" | python3 -c '
import sys, yaml
docs = [d for d in yaml.safe_load_all(sys.stdin) if d]
seen = set()
for d in docs:
    if d["kind"] not in ("Deployment", "Job"):
        continue
    seen.add(d["kind"])
    for c in d["spec"]["template"]["spec"]["containers"]:
        assert isinstance(c["image"], str) and c["image"].startswith("ci/loan\"\n"), c["image"]
        assert "command" not in c, c.get("command")
        if d["kind"] == "Deployment":
            assert "args" not in c, c.get("args")
        else:
            assert c["args"] == ["migrate"], c["args"]
assert seen == {"Deployment", "Job"}, seen
print("image: one scalar, no command or args added")' || { cat "$tmp/image.err" >&2; exit 1; }
helm template ci "$chart" $args | python3 scripts/ci/externalsecret-naming.py --env dev
helm template ci "$chart" -f "$chart/values-dev.yaml" $args | python3 scripts/ci/externalsecret-naming.py --env dev
# The checks themselves must catch a wrong render.
for wrong in "--set serviceAccount.name=some-other-service" \
             "--set externalSecret.serviceClientSecretName=dev/other-service/oidc-client"; do
  if helm template ci "$chart" $args $wrong 2>/dev/null | python3 scripts/ci/externalsecret-naming.py --env dev; then
    echo "externalsecret-naming accepted a wrong render ($wrong)" >&2
    exit 1
  fi
done
# Platform review round 2: NetworkPolicy is the mesh repo's (off by default, never a CIDR),
# the observability PodMonitor is the single scrape path (no ServiceMonitor), squad label set.
if grep -qE '^kind: (NetworkPolicy|ServiceMonitor)$' <<<"$rendered"; then
  echo "chart renders a NetworkPolicy or ServiceMonitor by default" >&2
  exit 1
fi
if helm template ci "$chart" $args --set networkPolicy.enabled=true | grep -qE 'ipBlock|cidr'; then
  echo "the opt-in NetworkPolicy admits a CIDR" >&2
  exit 1
fi
grep -q 'fintechbankx.io/service-id: svc-ln-loan-lifecycle' <<<"$deployment"
grep -q 'prometheus.io/port: "8081"' <<<"$deployment"
test "$(grep -c 'fintechbankx.io/squad: "lending"' <<<"$rendered")" -ge 5
# Credit reservation recovery sweep settings reach the pods (runbook: operator states).
grep -q '"CREDIT_RESERVATION_SWEEP_ENABLED": "true"' <<<"$rendered"
grep -q '"CREDIT_RESERVATION_SWEEP_INTERVAL": "PT1M"' <<<"$rendered"
grep -q '"CREDIT_RESERVATION_SWEEP_GRACE": "PT10M"' <<<"$rendered"
# Unanswered reserves are left for an operator until the customer release by reference is live.
grep -q '"CREDIT_RESERVATION_SWEEP_RELEASE_UNANSWERED": "false"' <<<"$rendered"
# A missing required setting must stop the render.
if helm template ci "$chart" --set image.repository=ci/x --set image.tag=ci > /dev/null 2>&1; then
  echo "chart rendered without its required settings" >&2
  exit 1
fi
# Platform round 7: the migration Job runs as its own hook ServiceAccount (no token, no IAM
# role), created before the Job, never the chart's account or the namespace default.
helm template ci "$chart" $args | python3 -c '
import sys, yaml
docs = [d for d in yaml.safe_load_all(sys.stdin) if d]
job = [d for d in docs if d["kind"] == "Job"][0]
name = job["spec"]["template"]["spec"].get("serviceAccountName")
sas = {d["metadata"]["name"]: d for d in docs if d["kind"] == "ServiceAccount"}
assert name and name in sas, (name, sorted(sas))
sa = sas[name]["metadata"]
dep = [d for d in docs if d["kind"] == "Deployment"][0]["spec"]["template"]["spec"]
assert name != dep.get("serviceAccountName"), name
assert sa["annotations"]["helm.sh/hook"] == job["metadata"]["annotations"]["helm.sh/hook"], sa
assert int(sa["annotations"]["helm.sh/hook-weight"]) < int(job["metadata"]["annotations"]["helm.sh/hook-weight"]), sa
assert sas[name].get("automountServiceAccountToken") is False and not sa.get("annotations", {}).get("eks.amazonaws.com/role-arn"), sa
assert job["spec"]["template"]["spec"]["automountServiceAccountToken"] is False
print("migration Job runs as hook ServiceAccount", name)'
