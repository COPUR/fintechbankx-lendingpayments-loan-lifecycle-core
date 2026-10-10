{{- define "loan.name" -}}
{{- .Chart.Name -}}
{{- end -}}

{{- /*
app.kubernetes.io/name is the service account name (platform contract) on every
pod, the migration Job's included: mesh NetworkPolicies grant Aurora egress by
that label only. app.kubernetes.io/component tells the pods apart (service for the
Deployment, db-migration for the Job; cicd-templates 335a345), so the Service,
PDB, NetworkPolicy, topology spread and Deployment never select the Job pod.
*/ -}}
{{- define "loan.baseLabels" -}}
app.kubernetes.io/name: {{ .Values.serviceAccount.name }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "loan.selectorLabels" -}}
{{ include "loan.baseLabels" . }}
app.kubernetes.io/component: service
{{- end -}}

{{- define "loan.labels" -}}
{{ include "loan.baseLabels" . }}
app.kubernetes.io/version: {{ .Values.image.tag | default .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
fintechbankx.io/squad: {{ required "squad is required (lending)" .Values.squad }}
{{- end -}}

{{- define "loan.secretName" -}}
{{ include "loan.name" . }}-db
{{- end -}}

{{- /*
Secrets Manager key for an ExternalSecret remoteRef. The platform ESO role may
read only secret:<env>/*, so every key must be <env>/loan-lifecycle-service/<name>
(e.g. dev/loan-lifecycle-service/db-app); "<env>-<slug>/..." is refused at sync.
Usage: include "loan.remoteKey" (list "externalSecret.remoteSecretName" .Values.externalSecret.remoteSecretName)
*/ -}}
{{- define "loan.remoteKey" -}}
{{- $field := index . 0 -}}
{{- $key := required (printf "%s is required" $field) (index . 1) -}}
{{- if not (regexMatch "^(dev|staging|prod)/loan-lifecycle-service/[a-z0-9-]+$" $key) -}}
{{- fail (printf "%s must be <env>/loan-lifecycle-service/<name> (env dev, staging or prod), got %q" $field $key) -}}
{{- end -}}
{{- $key -}}
{{- end -}}

{{- define "loan.migrationName" -}}
{{ include "loan.name" . }}-db-migration
{{- end -}}

{{- /*
Env name guard, this chart's additions on top of the vendored reference guard
(_fbx-guard.tpl, cicd-templates 2caa48f; round 6, guardrail 4a, loan #14 review).
The pods' only environment source is the ConfigMap rendered from .Values.config
(there is no extraEnv, and the ExternalSecret keys are fixed in the templates).
Names are read the way Spring's relaxed binding reads them (loan.envName: upper
case, '.' and '-' as '_'), and these are refused in every spelling, indexed
(_0, _1_) and dotted forms included:
  - spring.config.import|location|additional-location|name, indexed or suffixed
    (the reference refuses the exact names only);
  - spring.profiles.active|include|default|group, any suffix: the profile the
    pods run is .Values.kafka.profile, validated against the two shipped Kafka
    profiles, so no value can activate the local profile (application-local.yml
    switches the startup TLS assertion off);
  - fintechbankx.tls.* (the assertion's own switch);
  - spring.kafka.*security.protocol and spring.kafka.properties.* (the Kafka
    client's transport settings come from the profile, not from values).
The helper prints the reason (non-empty means rejected).
Usage: include "loan.guardEnvName" $key
*/ -}}
{{- define "loan.envName" -}}
{{- upper (replace "-" "_" (replace "." "_" (toString .))) -}}
{{- end -}}

{{- define "loan.guardEnvName" -}}
{{- $n := include "loan.envName" . -}}
{{- if regexMatch "^SPRING_CONFIG_(IMPORT|LOCATION|ADDITIONAL_?LOCATION|NAME)(_?[0-9]+)?_?$" $n -}}
a config import, location or name can load a file or config tree that overrides the datasource past the sslmode=verify-full check; the chart renders no config import
{{- else if regexMatch "^SPRING_PROFILES_(ACTIVE|INCLUDE|DEFAULT|GROUP)(_|$)" $n -}}
a profile can activate an application-<profile> config in the image (the local profile switches the startup TLS assertion off); the chart sets SPRING_PROFILES_ACTIVE from kafka.profile
{{- else if regexMatch "^FINTECHBANKX_?TLS" $n -}}
the chart never switches the startup TLS assertion (fintechbankx.tls.enforce) off or changes its settings
{{- else if regexMatch "^SPRING_?KAFKA_.*SECURITY_?PROTOCOL|^SPRING_?KAFKA_?PROPERTIES_" $n -}}
the Kafka client's security.protocol and client properties come from the kafka.profile, never from values (KAFKA_SECURITY_PROTOCOL is the one allowed place, SASL_SSL or SSL)
{{- end -}}
{{- end -}}

{{- /*
JVM options, this chart's additions: on top of fbx.validateJvmOptions, a value
may not mention fintechbankx (-Dfintechbankx.tls.enforce=false) or kafka
(-Dspring.kafka.security.protocol=PLAINTEXT).
Usage: include "loan.validateJvmOptions" (dict "where" "config.JAVA_TOOL_OPTIONS" "value" $value)
*/ -}}
{{- define "loan.validateJvmOptions" -}}
{{- include "fbx.validateJvmOptions" . -}}
{{- if regexMatch "(?i)fintechbankx|kafka" (toString .value) -}}
{{- fail (printf "%s must not mention fintechbankx or kafka (a JVM system property would switch the startup TLS assertion off or change the Kafka client's transport)" .where) -}}
{{- end -}}
{{- end -}}

{{- /*
Values guard, called once per render (configmap.yaml and migration-job.yaml):
  1. the vendored reference guard (fbx.validateDatabaseTls) over an adapter dict
     shaped like the shared chart's values: config, extraEnv (none here),
     externalSecret (keys fixed in the templates, so nothing to check) and
     databaseCa (always enabled: the bundle is always mounted);
  2. this chart's additions: loan.guardEnvName and loan.validateJvmOptions over
     every config key (and extraEnv name, should one ever be added);
  3. config.DB_URL is a PostgreSQL JDBC URL (fbx.validateJdbcUrl parses only
     those, so another driver would slip past it);
  4. kafka.profile is kafka-msk or kafka-strimzi (the only profiles the chart
     activates), and config.KAFKA_SECURITY_PROTOCOL, when set, is SASL_SSL or SSL.
Usage: include "loan.guardValues" .
*/ -}}
{{- define "loan.guardValues" -}}
{{- $extraEnv := .Values.extraEnv | default list -}}
{{- $shared := dict "javaToolOptions" "" "config" .Values.config "extraEnv" $extraEnv "externalSecret" (dict "enabled" .Values.externalSecret.enabled) "databaseCa" (merge (dict "enabled" true) .Values.databaseCa) -}}
{{- include "fbx.validateDatabaseTls" (dict "Values" $shared) -}}
{{- range $key, $value := .Values.config -}}
{{- with include "loan.guardEnvName" $key -}}
{{- fail (printf "config.%s is not allowed: %s" $key .) -}}
{{- end -}}
{{- if include "fbx.isJvmOptionsName" $key -}}
{{- include "loan.validateJvmOptions" (dict "where" (printf "config.%s" $key) "value" $value) -}}
{{- end -}}
{{- end -}}
{{- range $env := $extraEnv -}}
{{- $envName := toString (default "" $env.name) -}}
{{- with include "loan.guardEnvName" $envName -}}
{{- fail (printf "extraEnv must not set %s (value or valueFrom): %s" $envName .) -}}
{{- end -}}
{{- if include "fbx.isJvmOptionsName" $envName -}}
{{- include "loan.validateJvmOptions" (dict "where" (printf "extraEnv.%s" $envName) "value" (default "" $env.value)) -}}
{{- end -}}
{{- end -}}
{{- if not (regexMatch "(?i)^jdbc:(?:[a-z0-9-]+:)*postgresql:" (trim (toString .Values.config.DB_URL))) -}}
{{- fail "config.DB_URL must be a PostgreSQL JDBC URL (jdbc:postgresql://...?sslmode=verify-full&sslrootcert=<databaseCa bundle>)" -}}
{{- end -}}
{{- $profile := toString (required "kafka.profile is required (kafka-msk or kafka-strimzi)" .Values.kafka.profile) -}}
{{- if not (has $profile (list "kafka-msk" "kafka-strimzi")) -}}
{{- fail (printf "kafka.profile must be kafka-msk or kafka-strimzi, got %q (it is the only profile the pods activate; local switches the startup TLS assertion off)" $profile) -}}
{{- end -}}
{{- with .Values.config.KAFKA_SECURITY_PROTOCOL -}}
{{- if not (has (toString .) (list "SASL_SSL" "SSL")) -}}
{{- fail (printf "config.KAFKA_SECURITY_PROTOCOL must be SASL_SSL (kafka-msk) or SSL (kafka-strimzi), got %q" (toString .)) -}}
{{- end -}}
{{- end -}}
{{- end -}}
