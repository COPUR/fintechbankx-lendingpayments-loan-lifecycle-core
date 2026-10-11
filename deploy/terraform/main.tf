# AWS resources owned by svc-ln-loan-lifecycle: its own Aurora PostgreSQL
# cluster (db_ln_loan_lifecycle_<env>), encryption key, credentials and the
# IRSA role its pods use. Shared platform pieces (log group, SSM parameters,
# runtime secret) come from the platform microservice-base module.
#
# microservice-base (ref=main) names its runtime secret
# "<env>/<slug>/runtime" since terraform-modules #11 (457b504..d5d2548; it was
# "<env>-<slug>/runtime", outside the secret:<env>/* path the platform ESO role
# may read). This stack passes no runtime_secret_name and reads no runtime
# secret, so it takes the module default.

locals {
  service_id   = "svc-ln-loan-lifecycle"
  service_slug = "loan-lifecycle-service"
  name         = "${var.environment}-${local.service_slug}"
  database     = "db_ln_loan_lifecycle_${var.environment}"

  tags = merge({
    Service            = local.service_id
    BoundedContext     = "lending"
    OwningSquad        = "loan-lifecycle"
    Environment        = var.environment
    DataClassification = "confidential"
    ManagedBy          = "terraform"
  }, var.tags)
}

module "service_base" {
  source = "git::https://github.com/COPUR/fintechbankx-platform-delivery-iac-terraform-modules.git//modules/microservice-base?ref=main"

  service_name           = "Loan Lifecycle Service"
  service_slug           = local.service_slug
  environment            = var.environment
  database_engine        = "aurora-postgresql"
  cache_engine           = "none"
  identity_provider_url  = var.identity_provider_url
  observability_endpoint = var.observability_endpoint
  parameter_prefix       = "/fintechbankx"
  log_retention_days     = var.environment == "prod" ? 365 : 30
  tags                   = local.tags
}

# --- Encryption -------------------------------------------------------------

# The tag lets the platform's External Secrets Operator role (terraform-modules
# stacks/platform, module external-secrets-irsa) decrypt the db-app and
# db-migration secrets it syncs into the cluster; the key policy keeps the
# account default, so that
# IAM grant is enough. The service's own pods never read Secrets Manager.
resource "aws_kms_key" "database" {
  description             = "Encrypts ${local.database} storage, snapshots, logs and credentials"
  enable_key_rotation     = true
  deletion_window_in_days = 30

  tags = {
    "fintechbankx.io/secrets" = "true"
  }
}

resource "aws_kms_alias" "database" {
  name          = "alias/${local.name}-db"
  target_key_id = aws_kms_key.database.key_id
}

# --- Network ----------------------------------------------------------------

resource "aws_db_subnet_group" "database" {
  name       = "${local.name}-db"
  subnet_ids = var.private_subnet_ids
}

resource "aws_security_group" "database" {
  name        = "${local.name}-db"
  description = "PostgreSQL access for ${local.service_id} only"
  vpc_id      = var.vpc_id
}

resource "aws_vpc_security_group_ingress_rule" "postgres_from_workload" {
  security_group_id            = aws_security_group.database.id
  referenced_security_group_id = var.workload_security_group_id
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
  description                  = "PostgreSQL from ${local.service_id} pods"
}

# --- Aurora PostgreSQL (Serverless v2, Multi-AZ) ---------------------------

resource "aws_rds_cluster_parameter_group" "database" {
  name   = "${local.name}-aurora-pg16"
  family = "aurora-postgresql16"

  parameter {
    name  = "rds.force_ssl"
    value = "1"
  }

  parameter {
    name  = "log_min_duration_statement"
    value = "500"
  }
}

resource "aws_rds_cluster" "database" {
  cluster_identifier                  = "${local.name}-aurora"
  engine                              = "aurora-postgresql"
  engine_mode                         = "provisioned"
  engine_version                      = var.aurora_engine_version
  database_name                       = local.database
  master_username                     = "loan_admin"
  manage_master_user_password         = true
  master_user_secret_kms_key_id       = aws_kms_key.database.key_id
  db_subnet_group_name                = aws_db_subnet_group.database.name
  vpc_security_group_ids              = [aws_security_group.database.id]
  db_cluster_parameter_group_name     = aws_rds_cluster_parameter_group.database.name
  storage_encrypted                   = true
  kms_key_id                          = aws_kms_key.database.arn
  iam_database_authentication_enabled = true
  backup_retention_period             = var.backup_retention_days
  preferred_backup_window             = "01:00-02:00"
  preferred_maintenance_window        = "sun:03:00-sun:04:00"
  copy_tags_to_snapshot               = true
  deletion_protection                 = var.deletion_protection
  skip_final_snapshot                 = false
  final_snapshot_identifier           = "${local.name}-aurora-final"
  enabled_cloudwatch_logs_exports     = ["postgresql"]

  serverlessv2_scaling_configuration {
    min_capacity = var.aurora_min_capacity
    max_capacity = var.aurora_max_capacity
  }

  # AWS applies minor versions in the maintenance window
  # (auto_minor_version_upgrade); a plan must not try to roll them back.
  lifecycle {
    ignore_changes = [engine_version]
  }
}

resource "aws_rds_cluster_instance" "database" {
  count                                 = var.aurora_instance_count
  identifier                            = "${local.name}-aurora-${count.index + 1}"
  cluster_identifier                    = aws_rds_cluster.database.id
  instance_class                        = "db.serverless"
  engine                                = aws_rds_cluster.database.engine
  engine_version                        = aws_rds_cluster.database.engine_version
  db_subnet_group_name                  = aws_db_subnet_group.database.name
  publicly_accessible                   = false
  auto_minor_version_upgrade            = true
  performance_insights_enabled          = true
  performance_insights_kms_key_id       = aws_kms_key.database.arn
  performance_insights_retention_period = 7
  promotion_tier                        = count.index

  lifecycle {
    ignore_changes = [engine_version]
  }
}

# Runtime credential (role loan_lifecycle_app, DML only: V4 grants). The DBA
# bootstrap in docs/migration creates the role and writes
# {"username", "password"} here; Terraform never sees the value. External
# Secrets Operator (ClusterSecretStore aws-secrets-manager) syncs it into the
# pods' Kubernetes Secret. Same shape as terraform-modules aurora-postgresql
# app_secret_name (<env>/<slug>/<name>, 019a842), the migration secret next
# to it. Neither secret is tagged fintechbankx.io/value-in-state: Terraform
# never writes or reads their values, so the PR plan role (terraform-modules
# f8202f0) only describes them and never gets GetSecretValue on them.
resource "aws_secretsmanager_secret" "app_database" {
  # <env>/<service-slug>/...: the platform ESO role may read only
  # secret:<env>/*, so "<env>-<slug>/db-app" would be refused (same shape as
  # <env>/<service-slug>/oidc-client).
  name                    = "${var.environment}/${local.service_slug}/db-app"
  description             = "Application database credential for ${local.service_id}"
  kms_key_id              = aws_kms_key.database.arn
  recovery_window_in_days = 7
}

# Schema owner credential (role loan_lifecycle_owner, owns sc_ln_loan_lifecycle;
# Flyway only). Created and filled by the DBA bootstrap like the runtime one;
# synced only by the chart's pre-install/pre-upgrade migration Job (Helm value
# migration.remoteSecretName), never by the service pods. Same name as
# terraform-modules aurora-postgresql migration_secret_name.
resource "aws_secretsmanager_secret" "migration_database" {
  name                    = "${var.environment}/${local.service_slug}/db-migration"
  description             = "Schema owner (Flyway migration) credential for ${local.service_id}"
  kms_key_id              = aws_kms_key.database.arn
  recovery_window_in_days = 7
}

# --- IRSA: the pods' AWS identity -------------------------------------------

data "aws_iam_policy_document" "irsa_trust" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [var.eks_oidc_provider_arn]
    }

    condition {
      test     = "StringEquals"
      variable = "${var.eks_oidc_provider_url}:sub"
      values   = ["system:serviceaccount:${var.kubernetes_namespace}:${var.kubernetes_service_account}"]
    }

    condition {
      test     = "StringEquals"
      variable = "${var.eks_oidc_provider_url}:aud"
      values   = ["sts.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "workload" {
  name               = "${local.name}-irsa"
  assume_role_policy = data.aws_iam_policy_document.irsa_trust.json
}

# The pods' role holds no Secrets Manager or KMS grants: credentials reach the
# pod as a Kubernetes Secret synced by External Secrets Operator, and the
# service reads no SSM parameters. Its only AWS permissions are the MSK ones
# below.

# --- MSK: IAM client auth, scoped to this service's own topics --------------
# Platform contract: Kafka on AWS is MSK with IAM auth via the IRSA role.
# TODO: switch to the terraform-modules `msk-client-access` module once it is
# published on main.

locals {
  msk_topic_arn_prefix = var.msk_cluster_arn == "" ? "" : replace(var.msk_cluster_arn, ":cluster/", ":topic/")
  msk_group_arn_prefix = var.msk_cluster_arn == "" ? "" : replace(var.msk_cluster_arn, ":cluster/", ":group/")
}

data "aws_iam_policy_document" "msk" {
  count = var.msk_cluster_arn == "" ? 0 : 1

  # WriteDataIdempotently is a cluster action (the producer runs with
  # enable.idempotence=true): it is evaluated against the cluster ARN, so on a
  # topic ARN it would never match.
  statement {
    sid = "ConnectToCluster"
    actions = [
      "kafka-cluster:Connect",
      "kafka-cluster:DescribeCluster",
      "kafka-cluster:WriteDataIdempotently",
    ]
    resources = [var.msk_cluster_arn]
  }

  # One topic per aggregate (ADR-019): the Loan aggregate topic, and this
  # service's DLQ, where the repayment consumer writes records it gives up on.
  statement {
    sid = "ProduceOwnTopics"
    actions = [
      "kafka-cluster:DescribeTopic",
      "kafka-cluster:WriteData",
    ]
    resources = [
      "${local.msk_topic_arn_prefix}/evt.ln.loan.v1",
      "${local.msk_topic_arn_prefix}/evt.ln.loan.dlq.v1",
    ]
  }

  # The payment aggregate topic; the consumer handles only
  # Payments.Payment.LoanPaymentCompleted.v1 and skips the other event types.
  statement {
    sid = "ConsumePaymentEvents"
    actions = [
      "kafka-cluster:DescribeTopic",
      "kafka-cluster:ReadData",
    ]
    resources = ["${local.msk_topic_arn_prefix}/evt.pay.payment.v1"]
  }

  # Consumer groups of the repayment consumer only.
  statement {
    sid       = "OwnConsumerGroups"
    actions   = ["kafka-cluster:DescribeGroup", "kafka-cluster:AlterGroup"]
    resources = ["${local.msk_group_arn_prefix}/cg.svc-ln-loan-lifecycle.*"]
  }

  # No transactional-id statement: the service uses no Kafka transactions
  # (no transactional.id / transaction-id-prefix). If it ever does, grant
  # Describe/AlterTransactionalId on replace(cluster ARN, ":cluster/",
  # ":transactional-id/"), never on the :group/ prefix.
}

resource "aws_iam_role_policy" "msk" {
  count  = var.msk_cluster_arn == "" ? 0 : 1
  name   = "${local.name}-msk"
  role   = aws_iam_role.workload.id
  policy = data.aws_iam_policy_document.msk[0].json
}

# --- Alarms -----------------------------------------------------------------

resource "aws_cloudwatch_metric_alarm" "aurora_capacity" {
  alarm_name          = "${local.name}-aurora-acu-high"
  alarm_description   = "Aurora is near its max ACUs; raise aurora_max_capacity or look for a runaway query."
  namespace           = "AWS/RDS"
  metric_name         = "ACUUtilization"
  dimensions          = { DBClusterIdentifier = aws_rds_cluster.database.cluster_identifier }
  statistic           = "Average"
  period              = 300
  evaluation_periods  = 3
  threshold           = 85
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = var.alarm_topic_arn == "" ? [] : [var.alarm_topic_arn]
  ok_actions          = var.alarm_topic_arn == "" ? [] : [var.alarm_topic_arn]
}

# Connection budget: every replica at full pool (HPA maxReplicas x DB_POOL_MAX,
# 12 x 10 = 120 by default) plus headroom for the migration Job, the backfill
# and DBA sessions. The alarm fires only above that budget, i.e. on a
# connection leak or an unexpected client, not under normal peak load. Keep
# the variables in step with the Helm values (autoscaling.maxReplicas,
# config.DB_POOL_MAX), and keep the budget below Aurora's max_connections at
# aurora_min_capacity (Serverless v2 sizes it from capacity).
locals {
  db_connection_budget = var.service_max_replicas * var.db_pool_max + var.db_connection_headroom
}

resource "aws_cloudwatch_metric_alarm" "aurora_connections" {
  alarm_name          = "${local.name}-aurora-connections-high"
  alarm_description   = "Connections above the pool budget (${var.service_max_replicas} replicas x ${var.db_pool_max} + ${var.db_connection_headroom}): leak or unexpected client."
  namespace           = "AWS/RDS"
  metric_name         = "DatabaseConnections"
  dimensions          = { DBClusterIdentifier = aws_rds_cluster.database.cluster_identifier }
  statistic           = "Maximum"
  period              = 300
  evaluation_periods  = 2
  threshold           = local.db_connection_budget
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = var.alarm_topic_arn == "" ? [] : [var.alarm_topic_arn]
}
