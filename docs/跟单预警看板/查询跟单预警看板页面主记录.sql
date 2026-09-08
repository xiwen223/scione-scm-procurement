-- 查询分两步：先按日期/页面条件命中 groupKey，再恢复完整分组聚合主记录。
WITH base_rows AS (
    SELECT
        poi.id,
        poi.order_sn,
        CASE WHEN pp.is_combo = 1 THEN pp.sku ELSE poi.sku END AS sku,
        CASE
            WHEN pp.is_combo = 1 THEN COALESCE(pp.product_name, poi.product_name)
            ELSE poi.product_name
        END AS product_name,
        po.status_text,
        po.supplier_name,
        po.ware_house_name,
        po.opt_realname,
        pp.quantity_plan,
        poi.quantity_real,
        poi.quantity_entry,
        j.plan_sn,
        po.create_time,
        CASE
            WHEN pp.is_combo = 1 AND pp.sku = poi.sku THEN '组合采'
            WHEN pp.is_combo = 1 AND pp.sku <> poi.sku THEN '单品采'
            ELSE NULL
        END AS purchase_mode,
        CASE
            WHEN j.plan_sn IS NOT NULL AND pp.is_combo = 1 AND pp.sku <> poi.sku THEN '套装'
            WHEN j.plan_sn IS NOT NULL AND pp.is_combo IN (0, 1) THEN '单品'
            ELSE '合并下单'
        END AS ptype,
        CASE
            WHEN j.plan_sn IS NOT NULL AND pp.is_combo = 1 AND pp.sku <> poi.sku THEN j.plan_sn
            ELSE poi.order_sn
        END AS group_key,
        CASE
            WHEN poi.quantity_real IS NOT NULL
             AND poi.quantity_entry IS NOT NULL
             AND poi.quantity_entry >= poi.quantity_real
            THEN -1
            ELSE TIMESTAMPDIFF(DAY, po.create_time, NOW())
        END AS risk
    FROM lx_purchase_order_item poi
    INNER JOIN lx_purchase_order po ON po.order_sn = poi.order_sn
    LEFT JOIN JSON_TABLE(
        poi.relation_purchase_plan,
        '$[*]' COLUMNS (plan_sn VARCHAR(100) PATH '$')
    ) AS j ON j.plan_sn IS NOT NULL AND j.plan_sn <> ''
    LEFT JOIN lx_purchase_plan pp ON pp.plan_sn = j.plan_sn
    WHERE poi.is_delete = 0
      AND po.status NOT IN (-1, 124)
),
matched_group_keys AS (
    SELECT DISTINCT group_key
    FROM base_rows
    WHERE create_time >= '2026-08-01'
    -- 页面行级筛选也只在此处命中 groupKey，例如：
    -- AND supplier_name = '供应商名称'
    -- AND (order_sn LIKE '%关键词%' OR plan_sn LIKE '%关键词%' OR sku LIKE '%关键词%')
)
SELECT
    t.group_key,
    CASE
        WHEN GROUP_CONCAT(DISTINCT t.ptype) = '合并下单'
             AND COALESCE(GROUP_CONCAT(DISTINCT t.plan_sn), '') = ''
             AND COUNT(DISTINCT NULLIF(TRIM(t.sku), '')) = 1
            THEN '单品'
        WHEN GROUP_CONCAT(DISTINCT t.ptype) = '单品'
             AND (
                 COUNT(DISTINCT NULLIF(TRIM(t.sku), '')) > 1
                 OR COUNT(DISTINCT NULLIF(TRIM(t.plan_sn), '')) > 1
             )
            THEN '合并下单'
        ELSE GROUP_CONCAT(DISTINCT t.ptype)
    END AS type,
    CASE
        WHEN MAX(CASE WHEN t.purchase_mode = '组合采' THEN 1 ELSE 0 END) = 1 THEN '组合采'
        WHEN MAX(CASE WHEN t.purchase_mode = '单品采' THEN 1 ELSE 0 END) = 1 THEN '单品采'
        ELSE NULL
    END AS purchase_mode,
    GROUP_CONCAT(DISTINCT t.order_sn) AS orders,
    GROUP_CONCAT(DISTINCT t.plan_sn) AS plans,
    GROUP_CONCAT(DISTINCT t.opt_realname) AS opt_names,
    GROUP_CONCAT(DISTINCT t.supplier_name) AS suppliers,
    GROUP_CONCAT(DISTINCT t.ware_house_name) AS warehouses,
    IF(
        GROUP_CONCAT(DISTINCT t.ptype) <> '套装',
        SUM(t.quantity_real),
        MAX(t.quantity_plan)
    ) AS qty_plan,
    IF(
        GROUP_CONCAT(DISTINCT t.ptype) <> '套装',
        SUM(t.quantity_entry),
        IF(SUM(t.quantity_real) = SUM(t.quantity_entry), MAX(t.quantity_plan), 0)
    ) AS qty_ready,
    MAX(t.risk) AS risk
FROM base_rows t
INNER JOIN matched_group_keys matched ON matched.group_key = t.group_key
GROUP BY t.group_key
LIMIT 100;
