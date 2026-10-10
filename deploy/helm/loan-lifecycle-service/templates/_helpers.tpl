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
Env key guard (lending and payments round 5, governance answers 2b and 4). The
pods' only environment source is the ConfigMap rendered from .Values.config, and
no key in it may, whatever its case:
  - redirect Spring's configuration: SPRING_CONFIG_IMPORT, SPRING_CONFIG_LOCATION,
    SPRING_CONFIG_ADDITIONAL_LOCATION. A configtree is allowed only as a value this
    chart renders itself, on the fixed mount optional:configtree:/etc/fintechbankx/config/
    (the chart renders none today);
  - bypass the DB_URL guard in configmap.yaml: SPRING_DATASOURCE_URL, SPRING_FLYWAY_URL;
  - switch the service's startup TLS assertion off: FINTECHBANKX_TLS_ENFORCE
    (fintechbankx.tls.enforce is true in application.yml; only local runs and tests set it false).
Usage: include "loan.guardEnvKey" (list "config" $key)
*/ -}}
{{- define "loan.guardEnvKey" -}}
{{- $where := index . 0 -}}
{{- $key := index . 1 -}}
{{- $redirects := list "SPRING_CONFIG_IMPORT" "SPRING_CONFIG_LOCATION" "SPRING_CONFIG_ADDITIONAL_LOCATION" -}}
{{- $urls := list "SPRING_DATASOURCE_URL" "SPRING_FLYWAY_URL" -}}
{{- if has (upper $key) $redirects -}}
{{- fail (printf "%s.%s is not allowed: Spring configuration is never redirected from values; the only configtree is the chart-rendered optional:configtree:/etc/fintechbankx/config/" $where $key) -}}
{{- end -}}
{{- if has (upper $key) $urls -}}
{{- fail (printf "%s.%s is not allowed: the database URL goes through config.DB_URL, which must verify the Aurora certificate" $where $key) -}}
{{- end -}}
{{- if eq (upper $key) "FINTECHBANKX_TLS_ENFORCE" -}}
{{- fail (printf "%s.%s is not allowed: the chart never switches the startup TLS assertion off" $where $key) -}}
{{- end -}}
{{- end -}}
