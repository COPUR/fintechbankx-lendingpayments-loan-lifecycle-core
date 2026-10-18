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
app.kubernetes.io/name: {{ .Values.serviceAccount.name | quote }}
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
fintechbankx.io/squad: {{ required "squad is required (lending)" .Values.squad | quote }}
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
Values guard (round 6, guardrail 4a; re-vendored in round 7). The reference guard
is vendored unchanged from cicd-templates 73e1881 (templates/_fbx_helpers.tpl,
sha256 pinned in README "Chart guard" and checked by the deploy/helm job with
scripts/ci/verify-vendored-guard.sh); fbx.guard reads only .Values, so this
helper hands it an adapter dict shaped like the shared chart's values and
mapping every route this chart renders:
  - config: the ConfigMap (configmap.yaml), the pods' only environment source
    besides the chart's own DB_SSL_ROOT_CERT and SPRING_FLYWAY_ENABLED entries;
  - extraEnv, envFrom, extraEnvFrom: this chart renders none; the values are
    mapped so that adding one is refused or checked, never silently rendered;
  - javaToolOptions: none (the image sets JAVA_TOOL_OPTIONS); config may carry
    JAVA_TOOL_OPTIONS, JDK_JAVA_OPTIONS or _JAVA_OPTIONS, which the guard checks;
  - databaseCa: always enabled, the bundle is always mounted; mountPath, key
    and configMapName are the values deployment.yaml and migration-job.yaml
    mount, and the guard pins them to /etc/fintechbankx/rds-ca,
    global-bundle.pem and rds-ca-bundle (a null configMapName is refused);
  - kafka.runtime: kafka.profile mapped (kafka-msk -> msk, kafka-strimzi ->
    strimzi); SPRING_PROFILES_ACTIVE is rendered through fbx.kafkaProfile;
  - externalSecret: the fixed secretKeys of externalsecret.yaml (data) and of
    the migration Job's ExternalSecret (extraData) with their remoteSecretName
    values; no dataFrom.
This chart's own rules, kept because fbx.guard does not have them:
  1. kafka.profile must be kafka-msk or kafka-strimzi (the local profile
     switches the startup TLS assertion off; fbx.kafkaProfile knows runtimes,
     not profile names);
  2. no config key or extraEnv name under spring.kafka.properties.* (relaxed
     canonical form): sasl.jaas.config, sasl.client.callback.handler.class and
     the other client properties come from the Kafka profile only (fbx.guard
     checks the ssl.* names, security.protocol and
     endpoint.identification.algorithm only).
Removed in round 7 because fbx.guard now covers them: the "no kafka in a JVM
option" rule (fbx.validateJvmOptions refuses kafka and mongodb).
Called once at the top of deployment.yaml and of migration-job.yaml.
Usage: include "loan.guardValues" .
*/ -}}
{{- define "loan.kafkaRuntime" -}}
{{- $profile := toString (required "kafka.profile is required (kafka-msk or kafka-strimzi)" .Values.kafka.profile) -}}
{{- if eq $profile "kafka-msk" -}}msk
{{- else if eq $profile "kafka-strimzi" -}}strimzi
{{- else -}}
{{- fail (printf "kafka.profile must be kafka-msk or kafka-strimzi, got %q (it is the only profile the pods activate; local switches the startup TLS assertion off)" $profile) -}}
{{- end -}}
{{- end -}}

{{- define "loan.sharedValues" -}}
{{- $es := .Values.externalSecret -}}
{{- $data := list (dict "secretKey" "SPRING_DATASOURCE_PASSWORD" "property" "password" "remoteSecretName" (toString $es.remoteSecretName)) (dict "secretKey" "SERVICE_CLIENT_SECRET" "property" "client_secret" "remoteSecretName" (toString $es.serviceClientSecretName)) -}}
{{- $extraData := list -}}
{{- if .Values.migration.enabled -}}
{{- $extraData = list (dict "secretKey" "DB_MIGRATION_USERNAME" "property" "username" "remoteSecretName" (toString .Values.migration.remoteSecretName)) (dict "secretKey" "DB_MIGRATION_PASSWORD" "property" "password" "remoteSecretName" (toString .Values.migration.remoteSecretName)) -}}
{{- end -}}
{{- dict "config" .Values.config "extraEnv" (.Values.extraEnv | default list) "envFrom" (.Values.envFrom | default list) "extraEnvFrom" (.Values.extraEnvFrom | default list) "javaToolOptions" "" "databaseCa" (dict "enabled" true "mountPath" .Values.databaseCa.mountPath "key" .Values.databaseCa.key "configMapName" .Values.databaseCa.configMapName) "kafka" (dict "runtime" (include "loan.kafkaRuntime" .)) "externalSecret" (dict "enabled" (or $es.enabled .Values.migration.enabled) "data" $data "extraData" $extraData "dataFrom" list) | toJson -}}
{{- end -}}

{{- define "loan.springProfile" -}}
{{- include "fbx.kafkaProfile" (dict "Values" (include "loan.sharedValues" . | fromJson)) -}}
{{- end -}}

{{- define "loan.kafkaPropertiesName" -}}
{{- if regexMatch "^spring\\.kafka\\.properties(\\.|$)" (include "fbx.canonicalName" .) -}}true{{- end -}}
{{- end -}}

{{- define "loan.guardValues" -}}
{{- $shared := dict "Values" (include "loan.sharedValues" . | fromJson) -}}
{{- include "fbx.guard" $shared -}}
{{- range $key, $_ := .Values.config -}}
{{- if include "loan.kafkaPropertiesName" $key -}}
{{- fail (printf "config.%s is not allowed: the Kafka client properties (spring.kafka.properties.*) come from the kafka.profile, never from values" $key) -}}
{{- end -}}
{{- end -}}
{{- range $env := (.Values.extraEnv | default list) -}}
{{- $envName := toString (default "" $env.name) -}}
{{- if include "loan.kafkaPropertiesName" $envName -}}
{{- fail (printf "extraEnv must not set %s: the Kafka client properties (spring.kafka.properties.*) come from the kafka.profile, never from values" $envName) -}}
{{- end -}}
{{- end -}}
{{- range $key, $value := .Values.config -}}
{{- $readings := include "loan.journalReadings" $key -}}
{{- if regexMatch "(^|,)fintechbankx[.]journal([.,]|$)" $readings -}}
{{- fail (printf "config.%s is not allowed: fintechbankx.journal.* is set by the service, and the in-memory journal is for local and test runs only" $key) -}}
{{- end -}}
{{- if and (regexMatch "(^|,)journal[.]adapter(,|$)" $readings) (ne (toString $value) "http") -}}
{{- fail (printf "config.%s must be http: the journal is the accounts ledger, and the in-memory journal is refused in a deployed service" $key) -}}
{{- end -}}
{{- if include "fbx.isJvmOptionsName" $key -}}
{{- include "loan.refuseJournalJvmOption" (dict "where" (printf "config.%s" $key) "value" $value) -}}
{{- end -}}
{{- end -}}
{{- range $env := (.Values.extraEnv | default list) -}}
{{- $envName := toString (default "" $env.name) -}}
{{- if regexMatch "(^|,)(fintechbankx[.]journal([.,]|$)|journal[.]adapter(,|$))" (include "loan.journalReadings" $envName) -}}
{{- fail (printf "extraEnv must not set %s: the journal is the accounts ledger over http, set by the chart" $envName) -}}
{{- end -}}
{{- if and (include "fbx.isJvmOptionsName" $envName) (hasKey $env "value") -}}
{{- include "loan.refuseJournalJvmOption" (dict "where" (printf "extraEnv.%s" $envName) "value" $env.value) -}}
{{- end -}}
{{- end -}}
{{- include "loan.refuseJournalJvmOption" (dict "where" "javaToolOptions" "value" (.Values.javaToolOptions | default "")) -}}
{{- end -}}

{{/*
The four relaxed-binding readings of a name, comma-separated, that fbx.guard
checks for its own rules (_fbx_helpers.tpl): the environment reading
(fbx.canonicalName), the property reading (fbx.propertyName, '_' skipped, so
fintech_bankx.journal.allow-in-memory and journal.ada_pter bind) and both
readings of the name with its brackets removed (fbx.bracketlessName).
*/}}
{{- define "loan.journalReadings" -}}
{{- $n := toString . -}}
{{- $b := include "fbx.bracketlessName" $n -}}
{{- printf "%s,%s,%s,%s" (include "fbx.canonicalName" $n) (include "fbx.propertyName" $n) (include "fbx.canonicalName" $b) (include "fbx.propertyName" $b) -}}
{{- end -}}

{{/*
JVM options (JAVA_TOOL_OPTIONS, JDK_JAVA_OPTIONS, _JAVA_OPTIONS and
javaToolOptions) must not mention journal in any spelling: a system property
such as -Djournal.adapter=in-memory overrides the configuration (DevSecOps
55a686f B-2). Checked raw, with every character other than [a-z0-9] and white
space removed, and flat, as fbx.validateJvmOptions reads them.
*/}}
{{- define "loan.refuseJournalJvmOption" -}}
{{- $v := toString .value -}}
{{- $alt := regexReplaceAll "[^a-z0-9\\s]+" (lower $v) "" -}}
{{- $flat := regexReplaceAll "[^a-z0-9]+" (lower $v) "" -}}
{{- if or (regexMatch "(?i)journal" $v) (contains "journal" $alt) (contains "journal" $flat) -}}
{{- fail (printf "%s must not mention journal: the journal is the accounts ledger over http, set by the chart, and a system property would override it" .where) -}}
{{- end -}}
{{- end -}}
