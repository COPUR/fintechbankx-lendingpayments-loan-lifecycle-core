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
