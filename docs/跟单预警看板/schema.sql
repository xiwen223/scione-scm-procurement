-- =============================================================
-- 领星数据同步（lingxing-data-sync）数据库表结构
-- 由 sync_to_mysql.py 中静态列定义（*_COLS）生成
-- 引擎 InnoDB，字符集 utf8mb4
--
-- 说明：
--   所有表名带 lx_ 前缀（表示从领星同步）。
--   所有表（采购单/采购计划/操作日志/收货单/质检单/入库单/请款单/采购退货单/变更单/商品
--   及其明细、关系表、同步任务记录）的字段与类型均在脚本内静态定义，
--   程序不做运行时字段推断，也不 ALTER 已有表。
-- =============================================================
-- 同步任务执行记录（每次执行插入一条）

CREATE TABLE IF NOT EXISTS `lx_sync_task`
(
    `id`           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `task_type`    VARCHAR(32) NOT NULL COMMENT '同步类型',
    `start_date`   DATE        NOT NULL COMMENT '数据起始日期',
    `end_date`     DATE        NOT NULL COMMENT '数据结束日期',
    `status`       VARCHAR(16) NOT NULL DEFAULT 'running' COMMENT 'running/success/failed',
    `start_time`   DATETIME    NOT NULL COMMENT '开始时间',
    `end_time`     DATETIME COMMENT '结束时间',
    `duration_sec` INT COMMENT '耗时(秒)',
    `total_count`  BIGINT      NOT NULL DEFAULT 0 COMMENT '单据/商品总数',
    `item_count`   BIGINT      NOT NULL DEFAULT 0 COMMENT '明细/行数',
    `log_count`    INT         NOT NULL DEFAULT 0 COMMENT '操作日志条数',
    `error_msg`    TEXT COMMENT '错误信息',
    PRIMARY KEY (`id`),
    KEY `idx_type_date` (`task_type`, `start_date`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='每日同步任务执行记录';

-- 操作日志
CREATE TABLE IF NOT EXISTS `lx_business_log`
(
    `id`           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `business_id`  VARCHAR(64) NOT NULL COMMENT '业务单号（1_{order_sn}）',
    `realname`     VARCHAR(32) COMMENT '操作人',
    `ol_time`      DATETIME COMMENT '操作时间',
    `ol_type`      TINYINT COMMENT '操作类型',
    `ol_detail`    TEXT COMMENT '操作详情',
    `ol_uid`       BIGINT COMMENT '操作人 id',
    `ol_type_text` VARCHAR(64) COMMENT '操作类型名',
    PRIMARY KEY (`id`),
    KEY `idx_business_id` (`business_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='操作日志';

-- 采购计划（单表：一个计划单号对应一行 SKU）
CREATE TABLE IF NOT EXISTS `lx_purchase_plan`
(
    `id`                      BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `plan_sn`                 VARCHAR(32) NOT NULL COMMENT '采购计划编号',
    `ppg_sn`                  VARCHAR(32) COMMENT '采购计划批次号',
    `supplier_id`             BIGINT COMMENT '供应商id',
    `supplier_name`           VARCHAR(32) COMMENT '供应商名称',
    `status`                  INT COMMENT '状态值',
    `status_text`             VARCHAR(32) COMMENT '状态说明',
    `create_time`             DATETIME COMMENT '创建时间',
    `file`                    JSON COMMENT '附件',
    `update_time`             DATETIME COMMENT '更新时间',
    `gmt_modified`            DATETIME COMMENT '修改时间',
    `creator_uid`             BIGINT COMMENT '创建人id',
    `creator_real_name`       VARCHAR(32) COMMENT '创建人名称',
    `purchaser_id`            BIGINT COMMENT '采购方id',
    `purchaser_name`          VARCHAR(128) COMMENT '采购方名称',
    `cg_uid`                  BIGINT COMMENT '采购员id',
    `cg_opt_username`         VARCHAR(32) COMMENT '采购员名称',
    `wid`                     BIGINT COMMENT '仓库id',
    `warehouse_name`          VARCHAR(32) COMMENT '仓库名称',
    `remark`                  VARCHAR(512) COMMENT '产品备注',
    `plan_remark`             VARCHAR(512) COMMENT '备注',
    `is_related_process_plan` TINYINT COMMENT '是否关联了加工计划：0 否，1 是',
    `approval_status`         VARCHAR(16) COMMENT '审批状态（待审批/通过/驳回）',
    `approval_time`           DATETIME COMMENT '审批时间（仅通过/驳回时有值）',
    `approver`                VARCHAR(32) COMMENT '审批人（仅通过/驳回时有值）',
    `product_id`              BIGINT COMMENT '商品id',
    `product_name`            VARCHAR(255) COMMENT '品名',
    `sku`                     VARCHAR(64) COMMENT 'SKU',
    `fnsku`                   VARCHAR(32) COMMENT 'FNSKU',
    `pic_url`                 TEXT COMMENT '产品图片',
    `quantity_plan`           INT COMMENT '计划采购量',
    `expect_arrive_time`      DATE COMMENT '期望到货时间',
    `sid`                     BIGINT COMMENT '店铺id',
    `group_id`                BIGINT COMMENT '分组 id',
    `cg_box_pcs`              INT COMMENT '单箱数量',
    `is_combo`                TINYINT COMMENT '是否为组合商品：0 否，1 是',
    `is_aux`                  TINYINT COMMENT '是否为辅料：0 否，1 是',
    `spu`                     VARCHAR(32) COMMENT 'SPU',
    `spu_name`                VARCHAR(64) COMMENT '款名',
    `attribute`               JSON COMMENT '属性',
    `msku`                    JSON COMMENT 'MSKU',
    `seller_name`             VARCHAR(64) COMMENT '店铺名称',
    `marketplace`             VARCHAR(32) COMMENT '国家',
    `perm_uid`                JSON COMMENT '单据负责人uid',
    `perm_username`           JSON COMMENT '单据负责人名称',
    `custom_fields`           JSON COMMENT '自定义字段',
    `sync_date`               DATE COMMENT '同步日期',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_plan_sn` (`plan_sn`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='采购计划';

-- 采购计划组合关系（planInfo 接口 list[].combo_relation 展开）

CREATE TABLE IF NOT EXISTS `lx_plan_combo_relation`
(
    `id`                  BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `combo_id`            BIGINT      NOT NULL COMMENT '组合商品 id',
    `combo_sku`           VARCHAR(64) COMMENT '组合商品 SKU',
    `plan_sn`             VARCHAR(32) NOT NULL COMMENT '计划单号',
    `plan_id`             VARCHAR(64) COMMENT '计划 id（1_{plan_sn}）',
    `product_id`          BIGINT COMMENT '子商品 id',
    `sku`                 VARCHAR(64) COMMENT '子商品 SKU',
    `product_name`        VARCHAR(255) COMMENT '子商品品名',
    `pic_url`             TEXT COMMENT '图片',
    `model`               VARCHAR(128) COMMENT '型号',
    `category_name`       VARCHAR(128) COMMENT '分类名',
    `brand_name`          VARCHAR(64) COMMENT '品牌名',
    `spu`                 VARCHAR(32) COMMENT 'SPU',
    `spu_name`            VARCHAR(64) COMMENT 'SPU 名',
    `sku_identifier`      VARCHAR(512) COMMENT 'SKU 标识',
    `cid`                 BIGINT COMMENT '分类 id',
    `bid`                 BIGINT COMMENT '品牌 id',
    `ps_id`               BIGINT COMMENT '规格 id',
    `primary_supplier_id` BIGINT COMMENT '主供应商 id',
    `product_type`        TINYINT COMMENT '商品类型',
    `is_combo`            TINYINT COMMENT '是否组合',
    `combo_level`         TINYINT COMMENT '组合层级',
    `is_aux`              TINYINT COMMENT '是否辅料',
    `is_delete`           TINYINT COMMENT '是否删除',
    `quantity`            INT COMMENT '每套数量',
    `quantity_plan`       INT COMMENT '计划数量',
    `quantity_purchased`  INT COMMENT '已购数量',
    `cg_price`            DECIMAL(18, 4) COMMENT '采购价',
    `purchase_remark`     VARCHAR(512) COMMENT '采购备注',
    `attribute`           JSON COMMENT '属性',
    `children`            JSON COMMENT '下级组合',
    `sync_date`           DATE COMMENT '同步日期',
    PRIMARY KEY (`id`),
    KEY `idx_plan_sn` (`plan_sn`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='采购计划套装关系';

-- 采购单主表
CREATE TABLE IF NOT EXISTS `lx_purchase_order`
(
    `id`                     BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `order_sn`               VARCHAR(32) NOT NULL COMMENT '采购单号',
    `custom_order_sn`        VARCHAR(32) COMMENT '自定义单号',
    `alibaba_order_sn`       VARCHAR(64) COMMENT '1688订单号',
    `supplier_id`            BIGINT COMMENT '供应商id',
    `supplier_name`          VARCHAR(64) COMMENT '供应商',
    `contact_person`         VARCHAR(32) COMMENT '联系人',
    `contact_number`         VARCHAR(32) COMMENT '联系方式',
    `amount_total`           DECIMAL(18, 4) COMMENT '货物总价',
    `total_price`            DECIMAL(18, 4) COMMENT '总金额',
    `payment`                DECIMAL(18, 4) COMMENT '应付货款（手工）',
    `other_fee`              DECIMAL(18, 4) COMMENT '其他费用',
    `shipping_price`         DECIMAL(18, 4) COMMENT '运费',
    `purchase_rate`          DECIMAL(18, 6) COMMENT '采购汇率',
    `quantity_total`         INT COMMENT '采购总量',
    `quantity_real`          INT COMMENT '实际采购量',
    `quantity_receive`       INT COMMENT '待到货量',
    `quantity_entry`         INT COMMENT '入库量',
    `status`                 INT COMMENT '采购单状态',
    `status_text`            VARCHAR(32) COMMENT '状态说明',
    `sub_status`             INT COMMENT '1688订单状态',
    `sub_status_text`        VARCHAR(32) COMMENT '1688订单状态文本',
    `pay_status`             TINYINT COMMENT '付款状态',
    `pay_status_text`        VARCHAR(32) COMMENT '支付状态说明',
    `purchase_type`          TINYINT COMMENT '采购类型',
    `purchase_type_text`     VARCHAR(32) COMMENT '采购类型文本',
    `status_shipped`         TINYINT COMMENT '到货状态',
    `status_shipped_text`    VARCHAR(32) COMMENT '到货状态说明',
    `fee_part_type`          TINYINT COMMENT '费用分摊方式',
    `payment_method`         VARCHAR(64) COMMENT '支付方式',
    `settlement_method`      TINYINT COMMENT '结算方式',
    `qc_type`                TINYINT COMMENT '质检类型',
    `is_tax`                 TINYINT COMMENT '是否含税',
    `purchase_currency`      VARCHAR(16) COMMENT '采购币种',
    `other_currency`         VARCHAR(16) COMMENT '其他费用币种',
    `shipping_currency`      VARCHAR(16) COMMENT '运费币种',
    `icon`                   VARCHAR(16) COMMENT '币种符号',
    `wid`                    BIGINT COMMENT '仓库id',
    `ware_house_name`        VARCHAR(64) COMMENT '仓库名',
    `ware_house_bak_name`    VARCHAR(64) COMMENT '仓库名(备份)',
    `purchaser_id`           BIGINT COMMENT '采购方id',
    `opt_uid`                BIGINT COMMENT '采购员id',
    `opt_realname`           VARCHAR(32) COMMENT '操作人姓名',
    `last_uid`               BIGINT COMMENT '最后操作人员id',
    `last_realname`          VARCHAR(32) COMMENT '最后操作人姓名',
    `auditor_uid`            BIGINT COMMENT '审核人员id',
    `auditor_realname`       VARCHAR(32) COMMENT '审核人姓名',
    `create_time`            DATETIME COMMENT '创建时间',
    `update_time`            DATETIME COMMENT '采购单更新时间',
    `order_time`             DATETIME COMMENT '下单时间',
    `last_time`              DATETIME COMMENT '最后操作时间',
    `auditor_time`           DATETIME COMMENT '审核时间',
    `remark`                 VARCHAR(512) COMMENT '备注',
    `reason`                 VARCHAR(512) COMMENT '作废原因',
    `settlement_description` VARCHAR(512) COMMENT '结算描述',
    `audit_uids`             JSON COMMENT '审核人列表',
    `principal_uids`         JSON COMMENT '单据负责人信息',
    `custom_fields`          JSON COMMENT '自定义字段',
    `logistics_info`         JSON COMMENT '物流信息',
    `sync_date`              DATE COMMENT '同步日期',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_sn` (`order_sn`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='采购单';

-- 采购单明细
CREATE TABLE IF NOT EXISTS `lx_purchase_order_item`
(
    `id`                     BIGINT      NOT NULL COMMENT '采购单子项id（主键）',
    `order_sn`               VARCHAR(32) NOT NULL COMMENT '采购单号（关联主表）',
    `plan_sn`                VARCHAR(32) COMMENT '采购计划号',
    `relation_purchase_plan` JSON COMMENT '更多采购计划号',
    `product_id`             BIGINT COMMENT '本地产品id',
    `product_name`           VARCHAR(255) COMMENT '品名',
    `model`                  VARCHAR(128) COMMENT '型号',
    `sku`                    VARCHAR(64) COMMENT 'SKU',
    `fnsku`                  VARCHAR(32) COMMENT 'FNSKU',
    `spu`                    VARCHAR(32) COMMENT 'spu',
    `spu_name`               VARCHAR(64) COMMENT '款名',
    `sid`                    VARCHAR(64) COMMENT '店铺id',
    `msku`                   JSON COMMENT 'MSKU',
    `attribute`              JSON COMMENT '属性',
    `wid`                    BIGINT COMMENT '仓库id',
    `ware_house_name`        VARCHAR(64) COMMENT '仓库名称',
    `price`                  DECIMAL(18, 4) COMMENT '含税单价',
    `amount`                 DECIMAL(18, 4) COMMENT '价税合计',
    `tax_rate`               DECIMAL(18, 4) COMMENT '税率',
    `quantity_plan`          INT COMMENT '计划采购量',
    `quantity_real`          INT COMMENT '实际采购量',
    `quantity_per_case`      INT COMMENT '单箱数量',
    `quantity_return`        INT COMMENT '退货数',
    `quantity_exchange`      INT COMMENT '换货量',
    `cases_num`              INT COMMENT '箱数',
    `quantity_entry`         INT COMMENT '到货入库量',
    `quantity_receive`       INT COMMENT '待到货量',
    `quantity_qc`            INT COMMENT '质检量',
    `quantity_qc_prepare`    INT COMMENT '待质检量',
    `expect_arrive_time`     DATE COMMENT '期待到货时间',
    `remark`                 VARCHAR(512) COMMENT '备注',
    `is_delete`              TINYINT COMMENT '是否删除',
    `custom_fields`          JSON COMMENT '自定义字段',
    PRIMARY KEY (`id`),
    KEY `idx_order_sn` (`order_sn`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='采购单明细';



-- 变更单主表
CREATE TABLE IF NOT EXISTS `lx_purchase_change_order`
(
    `id`                  BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `order_sn`            VARCHAR(32) NOT NULL COMMENT '变更单号',
    `purchase_order_sn`   VARCHAR(32) COMMENT '采购单号',
    `custom_order_sn`     VARCHAR(32) COMMENT '自定义单号',
    `create_time`         DATETIME COMMENT '创建时间',
    `update_time`         DATETIME COMMENT '更新时间',
    `supplier_name`       VARCHAR(32) COMMENT '供应商',
    `old_supplier_name`   VARCHAR(32) COMMENT '旧供应商',
    `wid`                 BIGINT COMMENT '仓库id',
    `old_wid`             BIGINT COMMENT '旧仓库id',
    `ware_house_name`     VARCHAR(32) COMMENT '仓库',
    `old_ware_house_name` VARCHAR(32) COMMENT '旧仓库',
    `create_realname`     VARCHAR(32) COMMENT '创建人',
    `opt_realname`        VARCHAR(32) COMMENT '采购员',
    `old_opt_realname`    VARCHAR(32) COMMENT '变更前操作人',
    `remark`              VARCHAR(512) COMMENT '备注',
    `status`              BIGINT COMMENT '状态标识码',
    `status_text`         VARCHAR(32) COMMENT '状态文本',
    `icon`                VARCHAR(32) COMMENT '货币符号',
    `amount`              DECIMAL(18, 4) COMMENT '金额',
    `old_amount`          DECIMAL(18, 4) COMMENT '旧金额',
    `sync_date`           DATE COMMENT '同步日期',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_sn` (`order_sn`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='采购变更单';

-- 变更单明细
CREATE TABLE IF NOT EXISTS `lx_purchase_change_order_item`
(
    `id`                     BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `wid`                    BIGINT COMMENT '仓库id',
    `old_wid`                BIGINT COMMENT '旧仓库id',
    `ware_house_name`        VARCHAR(32) COMMENT '仓库',
    `old_ware_house_name`    VARCHAR(32) COMMENT '旧仓库',
    `spu`                    VARCHAR(32) COMMENT 'spu',
    `spu_name`               VARCHAR(64) COMMENT 'spu名称',
    `product_name`           VARCHAR(255) COMMENT '品名',
    `product_id`             BIGINT COMMENT '产品id',
    `sku`                    VARCHAR(32) COMMENT 'SKU',
    `attribute`              JSON COMMENT '属性信息',
    `is_aux`                 BIGINT COMMENT '是否辅料',
    `seller`                 JSON COMMENT '店铺列表',
    `msku`                   JSON COMMENT 'msku',
    `fnsku`                  VARCHAR(32) COMMENT 'FNSKU',
    `price`                  DECIMAL(18, 4) COMMENT '含税单价',
    `old_price`              DECIMAL(18, 4) COMMENT '旧含税单价',
    `price_without_tax`      DECIMAL(18, 4) COMMENT '不含税单价',
    `old_price_without_tax`  DECIMAL(18, 4) COMMENT '旧不含税单价',
    `tax_rate`               DECIMAL(18, 4) COMMENT '税率',
    `old_tax_rate`           DECIMAL(18, 4) COMMENT '旧税率',
    `amount`                 DECIMAL(18, 4) COMMENT '金额',
    `old_amount`             DECIMAL(18, 4) COMMENT '旧金额',
    `is_tax`                 BIGINT COMMENT '是否含税',
    `quantity_real`          BIGINT COMMENT '实际采购量',
    `old_quantity_real`      BIGINT COMMENT '旧实际采购量',
    `expect_arrive_time`     DATE COMMENT '预计到货时间',
    `old_expect_arrive_time` DATE COMMENT '原预计到货时间',
    `order_sn`               VARCHAR(32) NOT NULL COMMENT '变更单号（关联主表）',
    PRIMARY KEY (`id`),
    KEY `idx_order_sn` (`order_sn`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='采购变更单明细';

-- 收货单主表（字段类型为当前库实际结构快照）
CREATE TABLE IF NOT EXISTS `lx_receipt_order`
(
    `id`                  BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `order_sn`            VARCHAR(32) NOT NULL COMMENT '收货单号',
    `qc_type`             BIGINT COMMENT '质检类型：1 仓库质检，2 预检，3 免检',
    `status`              BIGINT COMMENT '状态：10 待收货，40 已完成',
    `create_time`         DATETIME COMMENT '创建时间',
    `update_time`         DATETIME COMMENT '更新时间',
    `create_uid`          BIGINT COMMENT '创建人 id',
    `create_realname`     VARCHAR(32) COMMENT '创建人',
    `receive_time`        DATETIME COMMENT '收货时间',
    `receive_uid`         BIGINT COMMENT '收货人 id',
    `receive_realname`    VARCHAR(32) COMMENT '收货人',
    `wid`                 BIGINT COMMENT '仓库 id',
    `order_type`          BIGINT COMMENT '收货类型：1 采购订单，2 委外订单',
    `business_order_sn`   VARCHAR(32) COMMENT '来源单号',
    `inbound_order_sns`   JSON COMMENT '入库单号',
    `supplier_id`         BIGINT COMMENT '供应商 id',
    `logistics_company`   VARCHAR(32) COMMENT '物流商',
    `logistics_order_no`  VARCHAR(64) COMMENT '物流单号',
    `expect_arrival_time` DATE COMMENT '预计到货时间',
    `shipping_currency`   VARCHAR(32) COMMENT '运费币种',
    `shipping_cost`       DECIMAL(18, 4) COMMENT '运费',
    `other_currency`      VARCHAR(32) COMMENT '其他费用币种',
    `other_fee`           DECIMAL(18, 4) COMMENT '其他费用',
    `opt_uid`             BIGINT COMMENT '采购员 id',
    `opt_realname`        VARCHAR(32) COMMENT '采购员',
    `remark`              VARCHAR(512) COMMENT '单据备注',
    `sync_date`           DATE COMMENT '同步日期',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_sn` (`order_sn`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='收货单';

-- 收货单明细（字段类型为当前库实际结构快照）
CREATE TABLE IF NOT EXISTS `lx_receipt_order_item`
(
    `id`                     BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `item_id`                BIGINT COMMENT '收货单子项 id',
    `order_item_id`          BIGINT COMMENT '采购单子项 id',
    `sku`                    VARCHAR(32) COMMENT 'SKU',
    `product_name`           VARCHAR(255) COMMENT '品名',
    `fnsku`                  VARCHAR(32) COMMENT 'FNSKU',
    `seller_id`              BIGINT COMMENT '店铺 id',
    `notice_num_total`       BIGINT COMMENT '通知收货量',
    `product_receive_num`    BIGINT COMMENT '收货量',
    `quantity_qc_prepare`    BIGINT COMMENT '待检量',
    `quantity_qc_already`    BIGINT COMMENT '已检量',
    `quality_examine_status` BIGINT COMMENT '质检状态：0 未质检，1 部分质检，2 完成质检',
    `remark`                 VARCHAR(512) COMMENT '备注',
    `qc_sn`                  JSON COMMENT '关联质检单号',
    `order_sn`               VARCHAR(32) NOT NULL COMMENT '收货单号（关联主表）',
    PRIMARY KEY (`id`),
    KEY `idx_order_sn` (`order_sn`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='收货单明细';

-- 质检单

CREATE TABLE IF NOT EXISTS `lx_qc_order`
(
    `id`                   BIGINT NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `qc_sn`                VARCHAR(32) NOT NULL COMMENT '质检单号',
    `order_type`           BIGINT COMMENT '订单类型：1 采购订单，2 委外订单',
    `qc_type`              BIGINT COMMENT '质检类型：1 仓库质检，2 预检，3 免检',
    `qc_method`            BIGINT COMMENT '质检方式：1 抽检，2 全检',
    `create_time`          DATETIME COMMENT '创建时间',
    `status`               BIGINT COMMENT '状态：0 待质检，1 已质检，2 已免检，10 已质检（撤销），20 已免检（撤销）',
    `order_sn`             VARCHAR(32) COMMENT '来源单号',
    `opt_uid`              BIGINT COMMENT '采购员 id',
    `opt_realname`         VARCHAR(32) COMMENT '采购员',
    `receive_uid`          BIGINT COMMENT '收货人 id',
    `receive_realname`     VARCHAR(32) COMMENT '收货人',
    `receive_time`         DATETIME COMMENT '到货时间',
    `qc_uid`               BIGINT COMMENT '质检人 id',
    `qc_realname`          VARCHAR(32) COMMENT '质检人',
    `qc_time`              DATETIME COMMENT '质检时间',
    `wid`                  BIGINT COMMENT '仓库 id',
    `supplier_id`          BIGINT COMMENT '供应商 id',
    `delivery_order_sn`    VARCHAR(32) COMMENT '收货单号',
    `delivery_item_id`     BIGINT COMMENT '收货单子项 id',
    `order_item_id`        BIGINT COMMENT '采购单子项 id',
    `sku`                  VARCHAR(64) COMMENT 'SKU',
    `product_name`         VARCHAR(255) COMMENT '品名',
    `fnsku`                VARCHAR(32) COMMENT 'FNSKU',
    `seller_id`            BIGINT COMMENT '店铺 id',
    `product_receive_num`  BIGINT COMMENT '质检量',
    `qc_num`               BIGINT COMMENT '抽检量',
    `qc_bad_num`           BIGINT COMMENT '抽检次品量',
    `not_qc_num`           BIGINT COMMENT '免检数量',
    `qc_rate_pass`         VARCHAR(32) COMMENT '抽检合格率',
    `qc_rate`              VARCHAR(32) COMMENT '抽检比例',
    `product_good_num`     BIGINT COMMENT '总良品量',
    `product_bad_num`      BIGINT COMMENT '总次品量',
    `whb_code_good`        VARCHAR(32) COMMENT '可用仓位',
    `whb_code_bad`         VARCHAR(32) COMMENT '次品仓位',
    `qc_remark`            VARCHAR(512) COMMENT '备注',
    `product_entry_total`  BIGINT COMMENT '入库总数量',
    `product_box_good_num` BIGINT COMMENT '装箱良品数',
    `custom_order_sn`      VARCHAR(32) COMMENT '自定义采购单号',
    `sync_date`            DATE COMMENT '同步日期',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_qc_sn` (`qc_sn`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='质检单';

-- 入库单主表

CREATE TABLE IF NOT EXISTS `lx_inbound_order`
(
    `id`                       BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `order_sn`                 VARCHAR(32) NOT NULL COMMENT '入库单号',
    `wid`                      BIGINT COMMENT '仓库id',
    `purchase_order_sn`        VARCHAR(32) COMMENT '采购单号',
    `custom_purchase_order_sn` VARCHAR(32) COMMENT '采购单自定义单号',
    `opt_uid`                  BIGINT COMMENT '操作人id',
    `opt_time`                 DATETIME COMMENT '入库时间',
    `inbound_time`             DATETIME COMMENT '自定义入库时间',
    `create_uid`               BIGINT COMMENT '创建人id',
    `create_time`              DATETIME COMMENT '创建时间',
    `commit_uid`               BIGINT COMMENT '提交人id',
    `commit_time`              DATETIME COMMENT '提交时间',
    `revoke_uid`               BIGINT COMMENT '撤销人id',
    `revoke_time`              DATETIME COMMENT '撤销时间',
    `supplier_id`              BIGINT COMMENT '供应商id',
    `supplier_name`            VARCHAR(32) COMMENT '供应商名称',
    `order_amount`             DECIMAL(18, 4) COMMENT '单据入库成本',
    `cg_uid`                   BIGINT COMMENT '采购员id',
    `return_price`             DECIMAL(18, 4) COMMENT '运费',
    `other_fee`                DECIMAL(18, 4) COMMENT '其他费用',
    `fee_part_type`            BIGINT COMMENT '费用分摊方式',
    `fee_part_type_text`       VARCHAR(32) COMMENT '费用分摊方式名称',
    `opt_realname`             VARCHAR(32) COMMENT '入库人姓名',
    `type`                     BIGINT COMMENT '入库类型',
    `type_text`                VARCHAR(32) COMMENT '入库类型名称',
    `custom_type_id`           BIGINT COMMENT '自定义类型ID',
    `custom_type_name`         VARCHAR(255) COMMENT '自定义类型名称',
    `status`                   BIGINT COMMENT '入库单状态',
    `status_text`              VARCHAR(32) COMMENT '入库单状态名称',
    `source_sn`                VARCHAR(32) COMMENT '关联单据号',
    `receipt_order_sn`         VARCHAR(32) COMMENT '收货单号',
    `currency`                 VARCHAR(32) COMMENT '运费币种',
    `cg_realname`              VARCHAR(32) COMMENT '采购员姓名',
    `create_realname`          VARCHAR(32) COMMENT '创建人名称',
    `commit_realname`          VARCHAR(32) COMMENT '提交人名称',
    `revoke_realname`          VARCHAR(32) COMMENT '撤销人名称',
    `ware_house_name`          VARCHAR(64) COMMENT '仓库名称',
    `remark`                   VARCHAR(512) COMMENT '单据备注',
    `increment_time`           DATETIME COMMENT '单据数据更新时间',
    `custom_fields`            JSON COMMENT '自定义字段',
    `inbound_idempotent_code`  VARCHAR(255) COMMENT '客户参考号，该字段校验唯一不可重复',
    `origin_shipping_fee`      DECIMAL(18, 4) COMMENT '原始运费',
    `origin_shipping_currency` VARCHAR(32) COMMENT '原始运费币种',
    `origin_purchase_rate`     DECIMAL(18, 4) COMMENT '原始汇率',
    `sync_date`                DATE COMMENT '同步日期',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_sn` (`order_sn`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='入库单';

-- 入库单明细
CREATE TABLE IF NOT EXISTS `lx_inbound_order_item`
(
    `id`                  BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `product_name`        VARCHAR(255) COMMENT '品名',
    `sku`                 VARCHAR(64) COMMENT 'SKU',
    `fnsku`               VARCHAR(32) COMMENT 'FNSKU',
    `seller_id`           BIGINT COMMENT '系统店铺id',
    `product_total`       BIGINT COMMENT '入库量',
    `product_good_num`    BIGINT COMMENT '良品量',
    `product_bad_num`     BIGINT COMMENT '次品量',
    `product_qc_num`      BIGINT COMMENT '待检量',
    `product_onshelf_num` BIGINT COMMENT '上架数量',
    `product_shelf_num`   BIGINT COMMENT '货架数量',
    `price`               DECIMAL(18, 4) COMMENT '采购单价',
    `amount`              DECIMAL(18, 4) COMMENT '入库成本',
    `fee_cost`            DECIMAL(18, 4) COMMENT '费用',
    `head_way_fee`        DECIMAL(18, 4) COMMENT '头程费用',
    `product_amounts`     DECIMAL(18, 4) COMMENT '货值',
    `single_fee`          DECIMAL(18, 4) COMMENT '单位费用',
    `unit_head_fee`       DECIMAL(18, 4) COMMENT '单件头程费',
    `single_stock_cost`   DECIMAL(18, 4) COMMENT '单位入库成本',
    `purchase_item_id`    BIGINT COMMENT '采购单子项id',
    `product_remark`      VARCHAR(512) COMMENT '产品备注',
    `custom_fields`       JSON COMMENT '自定义字段',
    `order_sn`            VARCHAR(32) NOT NULL COMMENT '入库单号（关联主表）',
    PRIMARY KEY (`id`),
    KEY `idx_order_sn` (`order_sn`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='入库单明细';

-- 请款单主表

CREATE TABLE IF NOT EXISTS `lx_request_funds_order`
(
    `id`                      BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `order_sn`                VARCHAR(32) NOT NULL COMMENT '请款单号',
    `type`                    BIGINT COMMENT '费用类型：1 采购货款，2 物流款，3 采购预付款，4 其他应付款',
    `object_type`             VARCHAR(32) COMMENT '付款对象类型',
    `object_name`             VARCHAR(32) COMMENT '付款对象名称',
    `payment_method`          VARCHAR(32) COMMENT '支付方式',
    `icon`                    VARCHAR(32) COMMENT '币种符号',
    `amount_total`            DECIMAL(18, 4) COMMENT '付款金额',
    `amount_paid`             DECIMAL(18, 4) COMMENT '已付金额',
    `amount_unpaid`           DECIMAL(18, 4) COMMENT '未付金额',
    `prepay_time`             DATE COMMENT '预计付款日期',
    `status`                  BIGINT COMMENT '状态：1 待付款，2 已完成，3 已作废，121 待审批，122 已驳回，124 审批流作废',
    `apply_user`              VARCHAR(32) COMMENT '申请人',
    `remark`                  VARCHAR(512) COMMENT '申请备注',
    `apply_time`              DATETIME COMMENT '申请时间',
    `pay_user`                VARCHAR(32) COMMENT '实际付款人',
    `real_pay_time`           DATE COMMENT '实际付款日期',
    `currency`                VARCHAR(32) COMMENT '币种',
    `settlement_method`       VARCHAR(32) COMMENT '结算方式',
    `sub_type`                BIGINT COMMENT '子类型：1 常规单据，2 1688 采购单',
    `trade_method`            BIGINT COMMENT '交易方式id',
    `trade_method_text`       VARCHAR(32) COMMENT '交易方式',
    `pay_currency`            VARCHAR(32) COMMENT '付款币种',
    `pay_currency_icon`       VARCHAR(32) COMMENT '付款币种符号',
    `pay_rate_type`           BIGINT COMMENT '付款汇率类型：1 付款指定汇率，2 请款指定汇率',
    `pay_rate_type_text`      VARCHAR(32) COMMENT '付款汇率类型',
    `pay_rate`                DECIMAL(18, 4) COMMENT '付款汇率',
    `payer_id`                BIGINT COMMENT '付款方',
    `payer_name`              VARCHAR(64) COMMENT '付款方名称',
    `detail_list`             JSON COMMENT '关联单号',
    `sync_date`               DATE COMMENT '同步日期',
    `settlement_account_name` JSON COMMENT '结算账户名',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_sn` (`order_sn`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='请款单';

-- 请款单关联单据明细

CREATE TABLE IF NOT EXISTS `lx_request_funds_order_item`
(
    `id`              BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `business_sn`     VARCHAR(32) COMMENT '业务单号',
    `custom_order_sn` VARCHAR(32) COMMENT '自定义单号',
    `order_sn`        VARCHAR(32) NOT NULL COMMENT '请款单号（关联主表）',
    PRIMARY KEY (`id`),
    KEY `idx_order_sn` (`order_sn`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='请款单明细';


-- 采购退货单主表
CREATE TABLE IF NOT EXISTS `lx_purchase_return_order`
(
    `id`                      BIGINT         NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `order_sn`                VARCHAR(32)    NOT NULL COMMENT '退货单号',
    `wid`                     BIGINT COMMENT '仓库id',
    `create_uid`              BIGINT COMMENT '创建人id',
    `create_realname`         VARCHAR(32) COMMENT '创建人名称',
    `create_time`             DATETIME COMMENT '创建时间',
    `last_time`               DATETIME COMMENT '更新时间',
    `buyer_uid`               BIGINT COMMENT '采购员id',
    `buyer_realname`          VARCHAR(32) COMMENT '采购员名称',
    `purchase_order_sn`       VARCHAR(32) COMMENT '采购单号',
    `supplier_id`             BIGINT COMMENT '供应商id',
    `supplier_name`           VARCHAR(64) COMMENT '供应商名称',
    `return_method`           TINYINT COMMENT '退货方式：1 退货扣款，2 退货补货',
    `replenish_method`        TINYINT COMMENT '补货方式：1 源单补货',
    `receipt_funds_order_sn`  VARCHAR(32) COMMENT '收款单号',
    `status`                  INT COMMENT '状态：121 待审批，122 已驳回，124 已作废（审批作废），10 已处理，20 已作废（单据作废），5 待退货',
    `purchase_currency`       VARCHAR(16) COMMENT '采购币种',
    `purchase_currency_icon`  VARCHAR(16) COMMENT '采购币种符号',
    `fee_part_type`           TINYINT COMMENT '费用分配方式：0 不分配，1 按金额，2 按数量',
    `shipping_currency`       VARCHAR(16) COMMENT '运费币种',
    `shipping_price`          DECIMAL(18, 4) COMMENT '退货运费',
    `other_currency`          VARCHAR(16) COMMENT '其他费用币种',
    `other_fee`               DECIMAL(18, 4) COMMENT '其他费用',
    `return_reason`           VARCHAR(512) COMMENT '退货原因',
    `return_amount_total`     DECIMAL(18, 4) COMMENT '退货总金额',
    `remark`                  VARCHAR(512) COMMENT '单据备注',
    `sync_date`               DATE COMMENT '同步日期',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_sn` (`order_sn`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='采购退货单';

-- 采购退货单明细
CREATE TABLE IF NOT EXISTS `lx_purchase_return_order_item`
(
    `id`                 BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `item_id`            BIGINT COMMENT '子项id',
    `spu_name`           VARCHAR(64) COMMENT '款名',
    `spu`                VARCHAR(32) COMMENT 'SPU',
    `product_name`       VARCHAR(255) COMMENT '品名',
    `sku`                VARCHAR(64) COMMENT 'SKU',
    `fnsku`              VARCHAR(32) COMMENT 'FNSKU',
    `msku`               JSON COMMENT 'MSKU',
    `attribute`          JSON COMMENT '属性',
    `seller_id`          BIGINT COMMENT '店铺id',
    `price`              DECIMAL(18, 4) COMMENT '含税单价',
    `quantity_real`      INT COMMENT '采购数量',
    `return_good_num`    INT COMMENT '良品退货量',
    `return_bad_num`     INT COMMENT '次品退货量',
    `replenish_num`      INT COMMENT '扣款数量',
    `deduction_amount`   DECIMAL(18, 4) COMMENT '退货金额',
    `expect_arrive_time` DATE COMMENT '预计到货时间',
    `remark`             VARCHAR(512) COMMENT '备注',
    `order_sn`           VARCHAR(32) NOT NULL COMMENT '退货单号（关联主表）',
    PRIMARY KEY (`id`),
    KEY `idx_order_sn` (`order_sn`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='采购退货单明细';


-- 收款单（queryReceiptFundsList 接口 data.list 展开）

CREATE TABLE IF NOT EXISTS `lx_receipt_funds_order`
(
    `id`           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `order_sn`     VARCHAR(32) NOT NULL COMMENT '收款单号',
    `amount`       DECIMAL(18, 4) COMMENT '收款金额',
    `create_time`  DATETIME COMMENT '创建时间',
    `create_user`  VARCHAR(32) COMMENT '创建人',
    `currency`     VARCHAR(16) COMMENT '币种代码',
    `icon`         VARCHAR(16) COMMENT '币种符号',
    `object_id`    BIGINT COMMENT '应收对象ID',
    `object_name`  VARCHAR(64) COMMENT '应收对象名称',
    `object_type`  VARCHAR(32) COMMENT '应收对象类型（supplier 供应商 / customer 客户）',
    `op_types`     JSON COMMENT '操作权限控制（审核/作废/删除/编辑按钮显隐）',
    `receipt_time` DATETIME COMMENT '收款时间',
    `remark`       VARCHAR(512) COMMENT '备注',
    `status`       INT COMMENT '状态：1 待收款，2 已完成，3 已作废，121 待审批，122 已驳回，124 审批流作废',
    `status_text`  VARCHAR(32) COMMENT '状态文本',
    `type`         TINYINT COMMENT '收款类型：1 采购退款，2 销售收款',
    `type_text`    VARCHAR(32) COMMENT '收款类型文本',
    `sync_date`    DATE COMMENT '同步日期',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_sn` (`order_sn`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='收款单';


-- 商品
CREATE TABLE IF NOT EXISTS `lx_product`
(
    `id`                    BIGINT NOT NULL COMMENT '商品 id',
    `cid`                   BIGINT COMMENT '分类 id',
    `bid`                   BIGINT COMMENT '品牌 id',
    `sku`                   VARCHAR(64) COMMENT 'SKU',
    `sku_identifier`        VARCHAR(512) COMMENT 'SKU 标识',
    `product_name`          VARCHAR(255) COMMENT '商品名',
    `pic_url`               TEXT COMMENT '图片',
    `cg_delivery`           BIGINT COMMENT '采购交期',
    `cg_transport_costs`    DECIMAL(18, 4) COMMENT '采购运输成本',
    `purchase_remark`       VARCHAR(512) COMMENT '采购备注',
    `cg_price`              DECIMAL(18, 4) COMMENT '采购价',
    `status`                BIGINT COMMENT '状态',
    `open_status`           BIGINT COMMENT '开启状态',
    `is_combo`              BIGINT COMMENT '是否组合',
    `create_time`           BIGINT COMMENT '创建时间',
    `update_time`           BIGINT COMMENT '更新时间',
    `product_developer_uid` BIGINT COMMENT '开发人 id',
    `cg_opt_uid`            BIGINT COMMENT '采购跟单 id',
    `cg_opt_username`       VARCHAR(32) COMMENT '采购跟单',
    `spu`                   VARCHAR(32) COMMENT 'SPU',
    `ps_id`                 BIGINT COMMENT '属性规格 id',
    `attribute`             JSON COMMENT '属性',
    `brand_name`            VARCHAR(64) COMMENT '品牌名',
    `category_name`         VARCHAR(128) COMMENT '分类名',
    `status_text`           VARCHAR(32) COMMENT '状态名',
    `product_developer`     VARCHAR(32) COMMENT '开发人',
    `supplier_quote`        JSON COMMENT '供应商报价',
    `aux_relation_list`     JSON COMMENT '辅料关系列表',
    `custom_fields`         JSON COMMENT '自定义字段',
    `global_tags`           JSON COMMENT '全局标签',
    `sync_date`             DATE COMMENT '同步日期',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='商品';

-- 组合商品关系
CREATE TABLE IF NOT EXISTS `lx_product_combo_relation`
(
    `id`         BIGINT NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `product_id` BIGINT COMMENT '子商品 id',
    `quantity`   BIGINT COMMENT '数量',
    `sku`        VARCHAR(32) COMMENT '子商品 SKU',
    `combo_id`   BIGINT NOT NULL COMMENT '组合商品 id',
    `combo_sku`  VARCHAR(32) COMMENT '组合商品 SKU',
    `sync_date`  DATE COMMENT '同步日期',
    PRIMARY KEY (`id`),
    KEY `idx_combo_id` (`combo_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='商品组合关系';


CREATE TABLE IF NOT EXISTS `lx_pp_po_sku_ass`
(
    `id`          bigint      NOT NULL COMMENT '主键',
    `plan_sn`     varchar(32) NOT NULL COMMENT '采购计划编号',
    `order_sn`    varchar(32) NOT NULL COMMENT '采购单号',
    `sku`         varchar(64) NOT NULL COMMENT '商品编码',
    `create_time` datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_pp_po_sku` (`plan_sn`,`order_sn`,`sku`) USING BTREE,
    KEY `uk_order_sn` (`order_sn`) USING BTREE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='采购计划-采购单关系';
