SELECT
    group_key
     ,GROUP_CONCAT(DISTINCT ptype) type
     ,GROUP_CONCAT(DISTINCT order_sn) orders
     ,GROUP_CONCAT(DISTINCT plan_sn) plans
     ,GROUP_CONCAT(DISTINCT opt_realname) opt_names
     ,GROUP_CONCAT(DISTINCT supplier_name) suppliers
     ,GROUP_CONCAT(DISTINCT ware_house_name) warehouses
     ,if(GROUP_CONCAT(DISTINCT ptype)='非套装',SUM(quantity_real),MAX(quantity_plan) ) qty_plan
     ,if(GROUP_CONCAT(DISTINCT ptype)='非套装',SUM(quantity_entry),if(SUM(quantity_real)=SUM(quantity_entry),MAX(quantity_plan),0)) qty_ready
     ,MAX(risk) risk
from (
         SELECT
             poi.id,
             poi.order_sn,
             poi.sku,
             po.status_text,
             po.supplier_name,
             po.ware_house_name,
             po.opt_realname,
             pp.sku main_sku,
             pp.is_combo is_combo,
             pp.quantity_plan,
             poi.quantity_real,
             poi.quantity_entry,
             j.plan_sn,
             case
                 when j.plan_sn is not null and pp.is_combo=0 then '非套装'
                 when j.plan_sn is not null and pp.is_combo=1 and poi.sku=pp.sku then '套装组合采'
                 when j.plan_sn is not null and pp.is_combo=1 and poi.sku<>pp.sku then '套装单品采'
                 when j.plan_sn is null then '无采购计划'
                 else '无采购计划'
                 end ptype,
             case
                 when j.plan_sn is not null and pp.is_combo=0 then poi.order_sn
                 when j.plan_sn is not null and pp.is_combo=1 then j.plan_sn
                 when j.plan_sn is null then poi.order_sn
                 else poi.order_sn
                 end group_key,
             poi.relation_purchase_plan,
             if(po.status_text='已完成',0,TIMESTAMPDIFF(DAY, po.create_time, NOW())) as risk
         FROM
             lx_purchase_order_item poi
                 INNER JOIN lx_purchase_order po ON po.order_sn = poi.order_sn
                 LEFT JOIN JSON_TABLE ( poi.relation_purchase_plan, '$[*]' COLUMNS ( plan_sn VARCHAR ( 100 ) PATH '$' ) ) AS j on j.plan_sn is not null and j.plan_sn<>''
                 LEFT JOIN lx_purchase_plan pp on pp.plan_sn=j.plan_sn
         WHERE poi.is_delete=0
           and po.create_time>='2026-08-01'
-- 		and pp.create_time>='2026-08-01'
           and po.STATUS NOT IN (- 1, 124 )
-- 			and j.plan_sn ='PP260731064'
-- 		and j.plan_sn  in ('PP260801012','PP260811033','PP260811048','PP260811067','PP260811034')
     ) t
group by
    group_key
    limit 100
;