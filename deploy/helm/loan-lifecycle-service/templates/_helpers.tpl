{{- define "loan.name" -}}
{{- .Chart.Name -}}
{{- end -}}

{{- /* app.kubernetes.io/name is the service account name (platform contract). */ -}}
{{- define "loan.selectorLabels" -}}
app.kubernetes.io/name: {{ .Values.serviceAccount.name }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "loan.labels" -}}
{{ include "loan.selectorLabels" . }}
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
