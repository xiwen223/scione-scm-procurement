CREATE TABLE `shipping_mark_import` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键自增',
    `bill_no` varchar(30) NOT NULL COMMENT '单据编号',
    `bill_name` varchar(100) NOT NULL COMMENT '上传文件名',
    `status` tinyint NOT NULL COMMENT '状态: 1 待处理,2处理中,3 已处理',
    `created_by` varchar(30) NOT NULL COMMENT '创建人账号',
    `creator` varchar(30) NOT NULL COMMENT '创建人姓名',
    `created_at` datetime(3) NOT NULL COMMENT '创建时间',
    `updated_by` varchar(30) NOT NULL COMMENT '修改人账号',
    `updator` varchar(30) NOT NULL COMMENT '修改人',
    `updated_at` datetime(3) NOT NULL COMMENT '修改时间',
    `processed_at` datetime(3) DEFAULT NULL COMMENT '处理完成时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_bill_no` (`bill_no`) USING BTREE,
    KEY `idx_status_created_at` (`status`, `created_at`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='箱唛导入主表';

CREATE TABLE `shipping_mark_detail` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
    `bill_no` varchar(30) NOT NULL COMMENT '父单据编号',
    `purchase_order_no` varchar(50) DEFAULT NULL COMMENT '采购单号',
    `sku_code` varchar(100) NOT NULL COMMENT '商品编码；空值以空字符串记录并在处理阶段失败',
    `sku_name` varchar(200) DEFAULT NULL COMMENT '商品名称',
    `sku_image` varchar(500) DEFAULT NULL COMMENT '产品图片本地公开 URL',
    `status` tinyint NOT NULL COMMENT '状态: 1 待处理,2生成中,3已生成,4已失败',
    `error_reason` varchar(200) DEFAULT NULL COMMENT '失败详情',
    `label_file` varchar(500) DEFAULT NULL COMMENT '生成的箱唛 xlsx 公开 URL',
    `created_by` varchar(30) NOT NULL COMMENT '创建人账号',
    `creator` varchar(30) NOT NULL COMMENT '创建人姓名',
    `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `updated_by` varchar(30) NOT NULL COMMENT '修改人账号',
    `updator` varchar(30) NOT NULL COMMENT '修改人姓名',
    `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间',
    PRIMARY KEY (`id`) USING BTREE,
    KEY `idx_bill_no` (`bill_no`) USING BTREE,
    KEY `idx_bill_no_status` (`bill_no`, `status`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='箱唛明细表';


CREATE TABLE `buyer_company` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `company_name` varchar(255) NOT NULL COMMENT '公司全称',
    `company_short_name` varchar(64) DEFAULT NULL COMMENT '公司简称',
    `credit_code` varchar(64) DEFAULT NULL COMMENT '统一社会信用代码',
    `legal_person` varchar(64) DEFAULT NULL COMMENT '法定代表人',
    `address` varchar(255) NOT NULL COMMENT '注册地址',
    `phone` varchar(20) DEFAULT NULL COMMENT '联系电话',
    `bank_name` varchar(128) DEFAULT NULL COMMENT '开户银行',
    `bank_account` varchar(64) DEFAULT NULL COMMENT '银行账号',
    `seal_url` varchar(512) DEFAULT NULL COMMENT '电子印章图片URL',
    `seal_base64` mediumtext COMMENT '电子印章Base64字符串',
    `fadada_seal_id` varchar(128) DEFAULT NULL COMMENT '法大大印章ID',
    `open_corpid` varchar(128) DEFAULT NULL COMMENT '法大大唯一公司ID',
    `priority` int NOT NULL DEFAULT 0 COMMENT '优先级（1为默认合同需方）',
    `is_active` tinyint NOT NULL DEFAULT 1 COMMENT '是否启用：0-否 1-是',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_credit_code` (`credit_code`),
    UNIQUE KEY `uk_open_corpid` (`open_corpid`),
    KEY `idx_priority` (`priority`),
    KEY `idx_is_active` (`is_active`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='需方公司配置表';
