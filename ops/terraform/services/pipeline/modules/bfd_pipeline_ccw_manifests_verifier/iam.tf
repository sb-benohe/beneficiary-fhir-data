data "aws_iam_policy_document" "logs_policy_doc" {
  statement {
    sid       = "AllowLogGroupCreate"
    actions   = ["logs:CreateLogGroup"]
    resources = ["arn:aws:logs:${local.region}:${local.account_id}:*"]
  }

  statement {
    sid     = "AllowLogStreamControl"
    actions = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = [
      "arn:aws:logs:${local.region}:${local.account_id}:log-group:/aws/lambda/${local.lambda_full_name}:*"
    ]
  }
}

resource "aws_iam_policy" "logs" {
  name = "${local.lambda_full_name}-logs"
  path = local.cloudtamer_iam_path
  description = join("", [
    "Permissions for the ${local.lambda_full_name} Lambda to write to its corresponding ",
    "CloudWatch Log Group and Log Stream",
  ])

  policy = data.aws_iam_policy_document.logs_policy_doc.json
}

data "aws_iam_policy_document" "ssm_policy_doc" {
  statement {
    actions = ["ssm:GetParameter"]
    resources = [
      "arn:aws:ssm:${local.region}:${local.account_id}:parameter/bfd/${local.env}/pipeline/sensitive/db/username",
      "arn:aws:ssm:${local.region}:${local.account_id}:parameter/bfd/${local.env}/pipeline/sensitive/db/password"
    ]
  }
}

resource "aws_iam_policy" "ssm" {
  name        = "${local.lambda_full_name}-ssm"
  path        = local.cloudtamer_iam_path
  description = "Permissions for the ${local.lambda_full_name} Lambda to get relevant SSM parameters"
  policy      = data.aws_iam_policy_document.ssm_policy_doc.json
}

data "aws_iam_policy_document" "rds_policy_doc" {
  statement {
    sid       = "AllowDescribeCluster"
    actions   = ["rds:DescribeDBClusters"]
    resources = [data.aws_rds_cluster.cluster.arn]
  }
}

resource "aws_iam_policy" "rds" {
  name        = "${local.lambda_full_name}-rds"
  path        = local.cloudtamer_iam_path
  description = "Permissions for the ${local.lambda_full_name} Lambda to describe the ${data.aws_rds_cluster.cluster.cluster_identifier} cluster"
  policy      = data.aws_iam_policy_document.rds_policy_doc.json
}

data "aws_iam_policy_document" "kms_policy_doc" {
  statement {
    sid       = "AllowDecryptionOfConfigKeys"
    actions   = ["kms:Decrypt"]
    resources = local.kms_config_key_arns
  }

  statement {
    sid = "AllowEncryptionAndDecryptionOfMasterKeys"
    actions = [
      "kms:Encrypt",
      "kms:Decrypt",
      "kms:ReEncrypt*",
      "kms:GenerateDataKey*",
      "kms:DescribeKey",
    ]
    resources = [local.kms_key_id]
  }
}

resource "aws_iam_policy" "kms" {
  name = "${local.lambda_full_name}-kms"
  path = local.cloudtamer_iam_path
  description = join("", [
    "Permissions for the ${local.lambda_full_name} Lambda to decrypt config KMS keys and encrypt ",
    "and decrypt master KMS keys for ${local.env}"
  ])

  policy = data.aws_iam_policy_document.kms_policy_doc.json
}

data "aws_iam_policy_document" "s3_policy_doc" {
  statement {
    actions   = ["s3:ListBucket"]
    resources = [data.aws_s3_bucket.etl_bucket.arn]
  }
}

resource "aws_iam_policy" "s3" {
  name        = "${local.lambda_full_name}-s3"
  path        = local.cloudtamer_iam_path
  description = "Permissions for the ${local.lambda_full_name} Lambda to list objects in the ${var.etl_bucket_id} Bucket"
  policy      = data.aws_iam_policy_document.s3_policy_doc.json
}

data "aws_iam_policy_document" "sns_policy_doc" {
  statement {
    sid       = "AllowPublish"
    actions   = ["SNS:Publish"]
    resources = data.aws_sns_topic.alert_topic[*].arn
  }
}

resource "aws_iam_policy" "sns" {
  name        = "${local.lambda_full_name}-sns"
  path        = local.cloudtamer_iam_path
  description = "Permissions for the ${local.lambda_full_name} Lambda to publish to the configured SNS Topic(s)"
  policy      = data.aws_iam_policy_document.sns_policy_doc.json
}

data "aws_iam_policy_document" "lambda_role_assume_policy_doc" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["lambda.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "this" {
  name                  = local.lambda_full_name
  path                  = local.cloudtamer_iam_path
  permissions_boundary  = data.aws_iam_policy.permissions_boundary.arn
  description           = "Role for ${local.lambda_full_name} Lambda"
  assume_role_policy    = data.aws_iam_policy_document.lambda_role_assume_policy_doc.json
  force_detach_policies = true
}

resource "aws_iam_role_policy_attachment" "this" {
  for_each = {
    logs = aws_iam_policy.logs.arn,
    ssm  = aws_iam_policy.ssm.arn,
    rds  = aws_iam_policy.rds.arn
    kms  = aws_iam_policy.kms.arn,
    s3   = aws_iam_policy.s3.arn,
    sns  = aws_iam_policy.sns.arn,
    vpc  = "arn:aws:iam::aws:policy/service-role/AWSLambdaVPCAccessExecutionRole"
  }

  role       = aws_iam_role.this.name
  policy_arn = each.value
}

data "aws_iam_policy_document" "scheduler_invoke_lambda_policy_doc" {
  statement {
    actions   = ["lambda:InvokeFunction"]
    resources = [aws_lambda_function.this.arn]
  }
}

resource "aws_iam_policy" "scheduler_invoke_lambda" {
  name = "${local.lambda_full_name}-scheduler-assumee-allow-lambda-invoke"
  path = local.cloudtamer_iam_path
  description = join("", [
    "Permissions for EventBridge Scheduler assumed role to invoke the ",
    "${local.lambda_full_name} Lambda"
  ])

  policy = data.aws_iam_policy_document.scheduler_invoke_lambda_policy_doc.json
}

data "aws_iam_policy_document" "scheduler_role_assume_policy_doc" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["scheduler.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "scheduler_assume_role" {
  name                 = "${local.lambda_full_name}-scheduler-assumee"
  path                 = local.cloudtamer_iam_path
  permissions_boundary = data.aws_iam_policy.permissions_boundary.arn
  description = join("", [
    "Role for EventBridge Scheduler allowing permissions to invoke the ",
    "${local.lambda_full_name} Lambda"
  ])
  assume_role_policy    = data.aws_iam_policy_document.scheduler_role_assume_policy_doc.json
  force_detach_policies = true
}

resource "aws_iam_role_policy_attachment" "invoke_lambda_policy_to_scheduler_assume_role" {
  role       = aws_iam_role.scheduler_assume_role.name
  policy_arn = aws_iam_policy.scheduler_invoke_lambda.arn
}
