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
{{- end -}}

{{- define "loan.secretName" -}}
{{ include "loan.name" . }}-db
{{- end -}}
