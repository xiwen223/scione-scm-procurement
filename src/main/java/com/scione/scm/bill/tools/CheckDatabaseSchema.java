package com.scione.scm.bill.tools;

import java.sql.*;

public class CheckDatabaseSchema {
    public static void main(String[] args) {
        String url = "jdbc:mysql://scione-dev.cepmyce8u68m.us-east-1.rds.amazonaws.com:3306/scione_scm_bill?useSSL=false&allowPublicKeyRetrieval=true";
        String user = "scione_dev";
        String password = "h9zv5N4jQkua";

        String[] tables = {"po_sync_record", "contract", "contract_item", "buyer_company", "po_sync_record_item"};

        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            Connection conn = DriverManager.getConnection(url, user, password);

            for (String table : tables) {
                System.out.println("\n" + "=".repeat(80));
                System.out.println("表: " + table);
                System.out.println("=".repeat(80));

                try {
                    Statement stmt = conn.createStatement();
                    ResultSet rs = stmt.executeQuery("DESC " + table);

                    System.out.printf("%-30s %-20s %-8s %-8s %-15s%n",
                        "Field", "Type", "Null", "Key", "Default");
                    System.out.println("-".repeat(80));

                    while (rs.next()) {
                        String field = rs.getString("Field");
                        String type = rs.getString("Type");
                        String nullable = rs.getString("Null");
                        String key = rs.getString("Key");
                        String defaultVal = rs.getString("Default");

                        System.out.printf("%-30s %-20s %-8s %-8s %-15s%n",
                            field, type, nullable, key,
                            defaultVal == null ? "NULL" : defaultVal);
                    }
                    rs.close();
                    stmt.close();
                } catch (SQLException e) {
                    System.out.println("查询失败: " + e.getMessage());
                }
            }
            conn.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
