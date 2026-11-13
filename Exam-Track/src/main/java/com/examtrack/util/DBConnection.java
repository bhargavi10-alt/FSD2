
package com.examtrack.util;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class DBConnection {

    private static final String URL =
            "jdbc:mysql://localhost:3306/examtrack";

    private static final String USER = "root";

    public static Connection getConnection() throws SQLException {
        String password = System.getenv("EXAMTRACK_DB_PASSWORD");

        if (password == null || password.isBlank()) {
            throw new SQLException(
                    "EXAMTRACK_DB_PASSWORD environment variable is not set."
            );
        }

        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new SQLException(
                    "MySQL JDBC driver could not be loaded.", e
            );
        }

        return DriverManager.getConnection(URL, USER, password);
    }
}
