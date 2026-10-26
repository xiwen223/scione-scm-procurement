-- 完整结构参考；2026-10-26按测试库实际字段、索引校对。已有库请对照现有结构按需ALTER，不要整份执行建表语句。
CREATE TABLE `shipping_mark_import` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键自增',
  `bill_no` varchar(30) COLLATE utf8mb4_general_ci NOT NULL COMMENT '单据编号',
  `bill_name` varchar(100) COLLATE utf8mb4_general_ci NOT NULL COMMENT '上传文件名',
  `status` tinyint NOT NULL COMMENT '状态: 1 待处理,2处理中,3 已处理',
  `created_by` varchar(30) COLLATE utf8mb4_general_ci NOT NULL COMMENT '创建人账号',
  `creator` varchar(30) COLLATE utf8mb4_general_ci NOT NULL COMMENT '创建人姓名',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` varchar(30) COLLATE utf8mb4_general_ci NOT NULL COMMENT '修改人账号',
  `updator` varchar(30) COLLATE utf8mb4_general_ci NOT NULL COMMENT '修改人',
  `updated_at` datetime(3) NOT NULL COMMENT '修改时间',
  `processed_at` datetime(3) DEFAULT NULL COMMENT '处理完成时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_bill_no` (`bill_no`) USING BTREE,
  KEY `idx_status_created_at` (`status`,`created_at`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='箱唛导入主表';

CREATE TABLE `shipping_mark_detail` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `bill_no` varchar(30) COLLATE utf8mb4_general_ci NOT NULL COMMENT '父单据编号',
  `purchase_order_no` varchar(50) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '采购单号',
  `sku_code` varchar(100) COLLATE utf8mb4_general_ci NOT NULL COMMENT '商品编码；空值以空字符串记录并在处理阶段失败',
  `sku_name` varchar(200) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '商品名称',
  `sku_image` varchar(500) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '产品图片本地公开 URL',
  `status` tinyint NOT NULL COMMENT '状态: 1 待处理,2生成中,3已生成,4已失败',
  `error_reason` varchar(200) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '失败详情',
  `label_file` varchar(500) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '生成的箱唛 xlsx 公开 URL',
  `created_by` varchar(30) COLLATE utf8mb4_general_ci NOT NULL COMMENT '创建人账号',
  `creator` varchar(30) COLLATE utf8mb4_general_ci NOT NULL COMMENT '创建人姓名',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_by` varchar(30) COLLATE utf8mb4_general_ci NOT NULL COMMENT '修改人账号',
  `updator` varchar(30) COLLATE utf8mb4_general_ci NOT NULL COMMENT '修改人姓名',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_bill_no` (`bill_no`) USING BTREE,
  KEY `idx_bill_no_status` (`bill_no`,`status`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='箱唛明细表';


CREATE TABLE `buyer_company` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `company_name` varchar(255) NOT NULL COMMENT '公司全称',
  `company_short_name` varchar(64) DEFAULT NULL COMMENT '公司简称',
  `open_corpid` varchar(50) DEFAULT NULL COMMENT '法大大唯一公司ID',
  `credit_code` varchar(64) DEFAULT NULL COMMENT '统一社会信用代码',
  `post_code` varchar(128) DEFAULT NULL COMMENT '邮编',
  `fax` varchar(128) DEFAULT NULL COMMENT '传真',
  `legal_person` varchar(64) DEFAULT NULL COMMENT '法定代表人',
  `address` varchar(255) DEFAULT NULL COMMENT '注册地址',
  `phone` varchar(20) DEFAULT NULL COMMENT '联系电话',
  `bank_name` varchar(128) DEFAULT NULL COMMENT '开户银行',
  `bank_account` varchar(64) DEFAULT NULL COMMENT '银行账号',
  `entity_id` varchar(50) DEFAULT NULL COMMENT '企业主体Id',
  `seal_name` varchar(50) DEFAULT NULL COMMENT '印章名称',
  `seal_url` varchar(512) DEFAULT NULL COMMENT '电子印章图片URL',
  `seal_base64` mediumtext COMMENT '电子印章Base64字符串',
  `fadada_seal_id` varchar(128) DEFAULT NULL COMMENT '法大大印章ID',
  `fadada_free_sign_business_id` varchar(128) DEFAULT NULL COMMENT '法大大免验证签场景码businessId',
  `fadada_free_sign_expire_time` datetime DEFAULT NULL COMMENT '法大大免验证签场景码到期时间',
  `seal_flow_status` tinyint COMMENT '印章审核状态，0：审核中；1：审核成功；2：审核失败',
  `seal_failed_reason` varchar(128) DEFAULT NULL COMMENT '印章审核不通过原因，仅审核失败（seal_flow_status=2）时有值',
  `seal_verify_id` bigint DEFAULT NULL COMMENT '印章审核工单ID。审核通过或不通过的结果会在回调事件中会带上该字段；用于回调定位公司（open_corpid 可重复，不能作定位键）',
  `priority` int DEFAULT '0' COMMENT '优先级（1为默认合同需方）',
  `is_active` tinyint NOT NULL DEFAULT '1' COMMENT '是否启用：0-否 1-是',
  `ident_status` tinyint NOT NULL DEFAULT '0' COMMENT '实名认证状态：1-已认证 0-未认证',
  `is_deleted` tinyint NOT NULL DEFAULT '0' COMMENT '是否删除：0-否 1-是（逻辑删除，不做物理删除）',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_credit_code` (`credit_code`),
  KEY `idx_priority` (`priority`),
  KEY `idx_is_active` (`is_active`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='需方公司配置表';

CREATE TABLE `contract_template` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `template_name` varchar(255) NOT NULL COMMENT '模板名称',
  `contract_type` tinyint NOT NULL COMMENT '合同类型：1-采购合同 2-购销合同 3-框架合同 4-人事合同',
  `object_key` varchar(512) DEFAULT NULL COMMENT '模板文件对象存储key',
  `fadada_template_id` varchar(128) DEFAULT NULL COMMENT '法大大模板ID',
  `is_default` tinyint NOT NULL DEFAULT '0' COMMENT '是否默认模板：0-否 1-是（每种合同类型只能有一个默认）',
  `is_active` tinyint NOT NULL DEFAULT '1' COMMENT '是否启用：0-否 1-是',
  `is_deleted` tinyint NOT NULL DEFAULT '0' COMMENT '是否删除：0-否 1-是（逻辑删除，不做物理删除）',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_contract_type` (`contract_type`),
  KEY `idx_is_active` (`is_active`),
  KEY `idx_is_default` (`is_default`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='合同模板表';


CREATE TABLE `procurement_operation_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `business_type` tinyint NOT NULL COMMENT '业务类型：1合同 2公司信息 3合同模板 4跟单情况导入',
  `data_id` bigint NOT NULL COMMENT '业务数据ID：根据business_type关联对应业务表主键',
  `data_name` varchar(255) NOT NULL COMMENT '业务数据名称快照',
  `operator_id` bigint DEFAULT NULL COMMENT '操作人ID（当前不采集，允许为空）',
  `operator_name` varchar(50) DEFAULT NULL COMMENT '操作人姓名（当前写入操作人邮箱）',
  `operation_type` varchar(50) NOT NULL COMMENT '操作类型：CREATE/UPDATE/DELETE/START_SIGN/URGE_SIGN/CANCEL/STATUS_CHANGE/IMPORT/IMPORT_FAILED等',
  `operation_desc` varchar(200) DEFAULT NULL COMMENT '操作描述（操作类型中文名：业务数据名称）',
  `operation_details` text COMMENT '操作详情JSON；修改记录字段变更，导入记录统计、失败说明及错误文件objectKey',
  `ip_address` varchar(50) DEFAULT NULL COMMENT 'IP地址（当前不采集，允许为空）',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
  PRIMARY KEY (`id`),
  KEY `idx_business_data` (`business_type`),
  KEY `idx_data_name` (`data_name`,`business_type`,`operation_type`),
  KEY `idx_operator_id` (`operator_id`),
  KEY `idx_operation_type` (`operation_type`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='供应链操作日志表';

-- scione_scm_procurement.po_sync_record 定义

CREATE TABLE `po_sync_record` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `purchase_order_no` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '采购单号（领星 order_sn）',
  `custom_order_sn` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '自定义采购单号（领星 custom_order_sn）',
  `supplier_id` bigint DEFAULT NULL COMMENT '供应商ID（领星 supplier_id）',
  `supplier_name` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '供应商名称（领星 supplier_name）',
  `default_account_name` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '领星供应商默认收款账户名称（payment_account_group 中 is_default=1 的 account_name）',
  `default_bank_account` varchar(128) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '领星供应商默认收款账户账号（默认账户 account_id）',
  `default_bank_name` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '领星供应商默认收款账户开户行（默认账户 bank_name）',
  `supplier_phone` varchar(50) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '供应商联系电话（领星 contact_number）',
  `contact_person` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '供应商联系人（领星 contact_person）',
  `po_status` smallint DEFAULT NULL COMMENT 'PO状态数字（领星 status：-1作废/3待提交/1待下单-已审核/2待签约(待发货)-待下单/9完成/121待审核/122驳回/124...）',
  `po_status_text` varchar(50) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'PO状态文本（领星 status_text）',
  `status_shipped` smallint DEFAULT NULL COMMENT '发货状态（领星 status_shipped：1未发货/2部分到货/3全部到货）',
  `status_shipped_text` varchar(50) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '发货状态文本（领星 status_shipped_text）',
  `amount_total` decimal(15,2) DEFAULT NULL COMMENT '采购总价（领星 amount_total）',
  `total_price` decimal(15,2) DEFAULT NULL COMMENT '总金额（领星 total_price）',
  `quantity_total` int DEFAULT NULL COMMENT '采购数量（领星 quantity_total）',
  `warehouse_name` varchar(128) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '仓库名（领星 ware_house_name）',
  `remark` varchar(500) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '备注（领星 remark）',
  `po_order_time` datetime(3) DEFAULT NULL COMMENT '下单时间（领星 order_time）',
  `po_create_time` datetime(3) DEFAULT NULL COMMENT 'PO创建时间（领星 create_time）',
  `po_update_time` datetime(3) DEFAULT NULL COMMENT 'PO更新时间（领星 update_time）',
  `has_contract` tinyint NOT NULL DEFAULT '0' COMMENT '是否已创建合同：0-否 1-是',
  `contract_id` bigint DEFAULT NULL COMMENT '关联的合同ID',
  `is_deleted` tinyint(1) NOT NULL DEFAULT '0' COMMENT '逻辑删除标记：0=未删除，1=已删除',
  `sync_time` datetime(3) DEFAULT NULL COMMENT '最后同步时间',
  `contract_created` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否已创建合同：0=否，1=是',
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_purchase_order_no` (`purchase_order_no`) USING BTREE,
  KEY `idx_po_status` (`po_status`) USING BTREE,
  KEY `idx_has_contract` (`has_contract`) USING BTREE,
  KEY `idx_sync_time` (`sync_time`) USING BTREE,
  KEY `idx_contract_created` (`contract_created`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='PO单同步记录表（单头）';

-- scione_scm_procurement.po_sync_record_item 定义

CREATE TABLE `po_sync_record_item` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `purchase_order_no` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '采购单号（冗余，关联 po_sync_record.purchase_order_no）',
  `lx_item_id` bigint DEFAULT NULL COMMENT '领星采购单明细ID（item_list.id）',
  `plan_sn` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '采购计划号（item_list.plan_sn）',
  `product_id` bigint DEFAULT NULL COMMENT '本地产品ID（item_list.product_id）',
  `product_name` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '品名（item_list.product_name）',
  `sku` varchar(128) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'SKU（item_list.sku）',
  `fnsku` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'FNSKU（item_list.fnsku）',
  `model` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '型号/规格（item_list.model）',
  `unit_price` decimal(15,4) DEFAULT NULL COMMENT '含税单价（item_list.price）',
  `unit_price_without_tax` decimal(15,4) DEFAULT NULL COMMENT '不含税单价：同步领星时按 is_tax 和 tax_rate 计算',
  `amount` decimal(15,2) DEFAULT NULL COMMENT '含税合计（item_list.amount）',
  `quantity_plan` int DEFAULT NULL COMMENT '计划采购量（item_list.quantity_plan）',
  `quantity_real` int DEFAULT NULL COMMENT '实际采购量（item_list.quantity_real）',
  `quantity_receive` int DEFAULT NULL COMMENT '入库数量（item_list.quantity_receive）',
  `cases_num` int DEFAULT '0' COMMENT '箱数',
  `quantity_per_case` int DEFAULT '0' COMMENT '单箱数量',
  `pic_url` varchar(500) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '产品图片URL',
  `tax_rate` varchar(20) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '税率（item_list.tax_rate）',
  `spu` varchar(128) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'SPU（item_list.spu）',
  `spu_name` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'SPU名称（item_list.spu_name）',
  `warehouse_name` varchar(128) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '仓库名（item_list.ware_house_name）',
  `expect_arrive_time` date DEFAULT NULL COMMENT '期望到货时间（item_list.expect_arrive_time）',
  `remark` varchar(500) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '备注（item_list.remark）',
  `attribute_json` text COLLATE utf8mb4_general_ci COMMENT '属性原文JSON（item_list.attribute）',
  `sync_time` datetime(3) DEFAULT NULL COMMENT '最后同步时间',
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_purchase_order_no` (`purchase_order_no`) USING BTREE,
  KEY `idx_sku` (`sku`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='PO单明细表';

-- scione_scm_procurement.contract 定义

CREATE TABLE `contract` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `contract_no` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '合同编号',
  `contract_name` varchar(255) COLLATE utf8mb4_general_ci NOT NULL COMMENT '合同名称',
  `contract_type` tinyint NOT NULL DEFAULT '1' COMMENT '合同类型：1-采购合同 2-购销合同 3-框架合同 4-人事合同',
  `purchase_order_no` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '采购单号（采购合同专用，关联 po_sync_record）',
  `source_type` tinyint NOT NULL DEFAULT '1' COMMENT '来源类型：1-领星采购单 2-黑湖',
  `supplier_id` bigint DEFAULT NULL COMMENT '供应商ID',
  `supplier_name` varchar(255) COLLATE utf8mb4_general_ci NOT NULL COMMENT '供应商名称',
  `supplier_phone` varchar(50) COLLATE utf8mb4_general_ci NOT NULL COMMENT '供应商联系电话（必填）',
  `contact_person` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '供应商联系人',
  `supplier_credit_code` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '供应商统一社会信用代码',
  `supplier_account_name` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '收款户名',
  `supplier_bank_name` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '开户行',
  `supplier_bank_account` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '银行账号',
  `prepay_percent` varchar(32) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '预付款比例',
  `settlement_method` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '结算方式',
  `supplier_address` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '供应商地址（可选）',
  `buyer_company_id` bigint NOT NULL COMMENT '需方公司ID',
  `buyer_company_name` varchar(255) COLLATE utf8mb4_general_ci NOT NULL COMMENT '需方公司名称',
  `buyer_company_code` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '需方公司信用代码',
  `buyer_address` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '需方地址（可选）',
  `post_code` varchar(128) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '需方邮编（冗余自 buyer_company）',
  `buyer_phone` varchar(20) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '需方电话（冗余自 buyer_company）',
  `fax` varchar(128) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '需方传真（冗余自 buyer_company）',
  `original_amount` decimal(15,2) NOT NULL COMMENT '原始金额',
  `discounted_amount` decimal(15,2) DEFAULT NULL COMMENT '折扣后金额（null表示无折扣）',
  `contract_amount` decimal(15,2) NOT NULL COMMENT '合同金额（实际金额=折扣后金额或原始金额）',
  `contract_date` date NOT NULL COMMENT '合同日期',
  `template_id` bigint DEFAULT NULL COMMENT '合同模板ID',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态：1-创建 2-签署中 3-履行中 4-完成 5-取消',
  `cancel_reason` varchar(500) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '取消原因',
  `cancel_time` datetime(3) DEFAULT NULL COMMENT '取消时间',
  `is_fadada_abolished` tinyint NOT NULL DEFAULT '0' COMMENT '法大大已完成作废：0-否 1-是',
  `fadada_contract_id` varchar(128) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '法大大合同ID',
  `fadada_task_id` varchar(128) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '法大大签署任务ID',
  `fadada_abolished_task_id` varchar(128) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '法大大解除协议任务ID',
  `contract_pdf_url` varchar(512) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '原始合同PDF URL',
  `contract_excel_url` varchar(512) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '已填充合同Excel文件URL，用于修改后增量更新并转PDF',
  `contract_excel_template_id` bigint DEFAULT NULL COMMENT '生成该Excel时使用的合同模板ID，用于模板变更后失效缓存',
  `signed_pdf_url` varchar(512) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '已签署合同PDF URL',
  `sign_start_time` datetime(3) DEFAULT NULL COMMENT '发起签署时间',
  `sign_complete_time` datetime(3) DEFAULT NULL COMMENT '签署完成时间',
  `fadada_abolished_time` datetime(3) DEFAULT NULL COMMENT '法大大作废完成时间',
  `complete_time` datetime(3) DEFAULT NULL COMMENT '完成时间',
  `error_message` varchar(500) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '失败原因',
  `creator_id` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '创建人ID',
  `creator_name` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '创建人姓名',
  `create_type` tinyint DEFAULT NULL COMMENT '创建方式：1-自动创建 2-手动创建',
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `is_deleted` tinyint NOT NULL DEFAULT '0' COMMENT '是否删除：0-否 1-是',
  `delivery_date` date DEFAULT NULL COMMENT '交货日期',
  `sign_launch_state` varchar(20) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '签署子状态：QUEUED/RUNNING/WAIT_CALLBACK/DONE/FAILED/UNKNOWN',
  `sign_launch_error` varchar(1000) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '签署发起失败原因',
  `sign_launch_operator` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '发起签署的操作人',
  `active_po_no` varchar(64) COLLATE utf8mb4_general_ci GENERATED ALWAYS AS ((case when ((`status` = 5) or (`is_deleted` = 1)) then NULL else `purchase_order_no` end)) VIRTUAL COMMENT '有效合同的采购单号（已取消/已删除置 NULL），仅用于唯一约束防重',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_contract_no` (`contract_no`) USING BTREE,
  UNIQUE KEY `uk_contract_active_po_no` (`active_po_no`),
  KEY `idx_contract_type` (`contract_type`) USING BTREE,
  KEY `idx_purchase_order_status` (`purchase_order_no`,`status`) USING BTREE,
  KEY `idx_supplier_id` (`supplier_id`) USING BTREE,
  KEY `idx_status` (`status`) USING BTREE,
  KEY `idx_contract_date` (`contract_date`) USING BTREE,
  KEY `idx_contract_excel_template_id` (`contract_excel_template_id`),
  KEY `idx_contract_sign_launch` (`sign_launch_state`,`is_deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='通用合同主表';

-- scione_scm_procurement.contract_item 定义

CREATE TABLE `contract_item` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `contract_id` bigint NOT NULL COMMENT '合同ID',
  `contract_no` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '合同编号（冗余）',
  `sku` varchar(128) COLLATE utf8mb4_general_ci NOT NULL COMMENT 'SKU编码',
  `product_id` bigint DEFAULT NULL COMMENT '商品ID（冗余自领星）',
  `product_name` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '商品名称（冗余自领星）',
  `specification` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '规格型号（冗余自领星）',
  `quantity` int DEFAULT NULL COMMENT '数量',
  `unit` varchar(20) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '单位',
  `unit_price` decimal(15,4) DEFAULT NULL COMMENT '单价',
  `amount` decimal(15,2) DEFAULT NULL COMMENT '金额小计',
  `delivery_date` date DEFAULT NULL COMMENT '期望交货日期',
  `warehouse_name` varchar(128) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '仓库名称（冗余自领星）',
  `remark` varchar(500) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '备注',
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `cases_num` int DEFAULT NULL COMMENT '箱数',
  `quantity_per_case` int DEFAULT NULL COMMENT '每箱数量',
  `pic_url` varchar(500) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '图片URL',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_contract_id` (`contract_id`) USING BTREE,
  KEY `idx_contract_no` (`contract_no`) USING BTREE,
  KEY `idx_sku` (`sku`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='合同明细表';

-- ============================================================
-- 催办业务表：新建库参考结构（含免登录外链浏览字段）。
-- 已有库不要整份执行；先核对字段，再按差异单独迁移，不删除已有业务数据。
-- ============================================================

CREATE TABLE `reminder_todo` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键，用于关联原因历史和推送记录',
  `node_code` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '催办节点编码，与Nacos配置一致，例如PO_CREATE表示PO创建未完成',
  `business_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '业务单据类型：PP采购计划、PO采购单、RF请款单、CR收货单、PACKING装箱任务、SHIPMENT_PLAN发货计划',
  `business_sn` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '被催办的业务单据编号，例如PO261016048',
  `item_key` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT '' COMMENT '明细维度键；当催办需要细分到商品时，区分同一单据里的不同 SKU；整单催办不用填,待下单填 ''；SKU-A 缺交期填 SKU-A',
  `round_key` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '固定ONCE加责任人SHA-256前16位；同节点、单据、明细、责任人累计催办次数，不因再次进入节点重置',
  `entered_at` datetime NOT NULL COMMENT '本轮进入催办节点的业务时间，用于计算卡滞时长',
  `assignee_userid` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin DEFAULT NULL COMMENT '当前责任人的企微userid；未完成映射时为空，不发送消息',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '待办状态：0进行中，1已完成，2已取消；发送成功不代表待办完成',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '待办记录创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '待办记录最后修改时间',
  `view_token` char(32) DEFAULT NULL COMMENT '免登录外链凭证；同一责任人的普通节点共用，关联货件使用独立凭证',
  `first_viewed_at` datetime DEFAULT NULL COMMENT '首次打开时间',
  `last_viewed_at` datetime DEFAULT NULL COMMENT '最近打开时间',
  `view_count` int NOT NULL DEFAULT '0' COMMENT '打开次数',
  `last_view_ip` varchar(64) DEFAULT NULL,
  `last_view_ua` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_todo_round` (`node_code`,`business_type`,`business_sn`,`item_key`,`round_key`) USING BTREE,
  KEY `idx_node_status` (`node_code`,`status`) USING BTREE,
  KEY `idx_assignee_status` (`assignee_userid`,`status`) USING BTREE,
  KEY `idx_view_token` (`view_token`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='催办待办表';

CREATE TABLE `reminder_reason_history` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键，唯一标识一条原因记录',
  `todo_id` bigint NOT NULL COMMENT '所属催办待办id，关联reminder_todo.id',
  `reason` varchar(150) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '本次填写的卡滞原因，最多150个字符',
  `author_userid` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '原因归属人员的企微userid，从当前token对应待办取得；不代表已验证实际填写人身份',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '本次原因提交时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_todo_time` (`todo_id`,`created_at`,`id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='卡滞原因历史表';

CREATE TABLE `reminder_push_record` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键；消息批次的id同时作为其待办明细的parent_id',
  `record_type` tinyint NOT NULL COMMENT '记录类型：1消息批次，2待办发送明细，3人员异常未发送记录；3不计次数、去重或自动重试',
  `parent_id` bigint DEFAULT NULL COMMENT '待办明细所属的消息批次id，关联本表中record_type=1的记录；例如三条明细的parent_id均为500，表示它们属于同一条汇总消息',
  `todo_id` bigint DEFAULT NULL COMMENT '被提醒的催办待办id，关联reminder_todo.id；通过该字段查到对应单据、催办节点和轮次，也用于查询某条待办的历史提醒记录',
  `reminder_stage` tinyint DEFAULT NULL COMMENT '待办明细的提醒阶段：1第一阶段，2第二阶段；普通节点使用1，货物备齐节点用1表示距交期≤8天提醒、2表示距交期≤2天且未创建CR单的提醒',
  `scheduled_at` datetime NOT NULL COMMENT '本次提醒所属的计划执行时点；例如计划09:00发送，失败后09:05重试仍保存09:00，用于识别同一执行时点的提醒记录',
  `recipient_userid` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin DEFAULT NULL COMMENT '消息批次实际在群内@的人员企微userid，不是领星用户id或人员姓名；用于确定消息接收人并追溯历史提醒对象',
  `route_key` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin DEFAULT NULL COMMENT '预留路由字段；当前代码不使用，普通节点使用send.webhook-key，关联货件使用SMTP',
  `send_status` tinyint DEFAULT NULL COMMENT '消息批次状态：0未发送，1发送中，2渠道明确接受，3明确失败，4结果未知；未知不自动重试，不代表业务完成',
  `sent_at` datetime DEFAULT NULL COMMENT '消息批次最近一次实际发起群机器人或SMTP发送的时间，重试时更新',
  `send_result` json DEFAULT NULL COMMENT '消息上下文、渠道WEBHOOK或EMAIL、收件邮箱、尝试次数和发送结果；不保存发送凭据',
  `next_retry_at` datetime DEFAULT NULL COMMENT '明确未发送或失败且允许重试的下次执行时间；结果未知不安排自动重试',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '本条批次或待办明细记录首次创建的时间；同一次逻辑发送重试时沿用原记录，不改变创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '本条记录最后修改时间；更新发送状态、发送结果或重试安排时自动更新，用于追踪记录变化及排查长时间处于发送中的情况',
  `node_code` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin DEFAULT NULL COMMENT '未发送异常对应的催办节点；record_type=3使用',
  `business_sn` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin DEFAULT NULL COMMENT '未发送的业务单号；不要求已经建待办',
  `receiver_key` varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin DEFAULT NULL COMMENT '应接收人的配置映射键；来源人员不明时为空，不猜接收人',
  `error_code` varchar(64) DEFAULT NULL COMMENT '未发送原因分类：SOURCE_RECIPIENT_UNKNOWN、RECIPIENT_MAPPING_INVALID',
  `error_message` varchar(500) DEFAULT NULL COMMENT '明确未发送的原因，不保存认证凭据',
  `skip_key` char(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '节点、时点、单号、人员映射键和阶段的摘要；同一异常重跑幂等',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_skip_key` (`skip_key`),
  KEY `idx_parent` (`parent_id`) USING BTREE,
  KEY `idx_todo_stage` (`todo_id`,`reminder_stage`) USING BTREE,
  KEY `idx_retry` (`record_type`,`send_status`,`next_retry_at`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='催办推送记录表，保存群机器人汇总消息、关联待办明细及发送结果';

CREATE TABLE `reminder_excel_import` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `order_sn` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '采购单号，与SKU组成唯一键',
  `sku` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '真实采购SKU，同一组合的多条采购明细共用本条人工信息',
  `follower_name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '跟单员姓名',
  `expected_delivery_date` date DEFAULT NULL COMMENT '预计交期；最新导入留空清除旧值',
  `reason` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '原因；最新导入留空清除旧值',
  `remark` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '备注；最新导入留空清除旧值',
  `updated_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '最近维护人工信息的操作人',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近维护时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_order_sku` (`order_sn`,`sku`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='Excel导入表，保存采购单号加SKU维度的人工跟单信息';

-- Excel人工信息表共10个字段；采购单号+真实SKU唯一，不增加虚构采购计划或确认状态字段。

-- 自动合同采购单同步专用进度，不与催办lx_sync_state_h混用。
-- 部署新版本前执行本段一次。已有采购单/合同数据不需要清空。
CREATE TABLE IF NOT EXISTS `po_sync_state` (
  `source_code` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '同步来源，合同采购单固定contract_po',
  `updated_until` datetime NOT NULL COMMENT '首轮查询下界；之后仅在采购单及合同处理无失败时推进为查询截止时间，北京时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '成功进度最近更新时间',
  PRIMARY KEY (`source_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='自动合同采购单同步成功进度';

-- 未初始化进度时，可在Nacos配置contract.auto-create.initial-start-time指定首轮回补起点。
-- 如不配置，首轮兼容原来的最近6分钟；指定时间的手动回补不会改变自动进度。
-- Java与Python请求协调仅在连接同一MySQL实例、使用同一appId和接口路径时生效。
