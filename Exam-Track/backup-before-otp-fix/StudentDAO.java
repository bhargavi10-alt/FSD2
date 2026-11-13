package com.examtrack.dao;

import com.examtrack.model.Student;
import com.examtrack.util.DBConnection;
import com.examtrack.util.PasswordUtil;

import java.security.GeneralSecurityException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;

public class StudentDAO {

    // Public constructor for existing servlets
    public StudentDAO() {
    }

    private String normalizeEmail(String email) {
        if (email == null) {
            return null;
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    // Check whether an email is already registered
    public boolean emailExists(String email) throws SQLException {
        String normalizedEmail = normalizeEmail(email);

        if (normalizedEmail == null || normalizedEmail.isBlank()) {
            return false;
        }

        String sql = "SELECT 1 FROM students WHERE email = ? LIMIT 1";

        try (Connection connection = DBConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, normalizedEmail);

            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    // Create an email-only account after OTP verification
    public boolean createEmailAccount(String email) throws SQLException {
        String normalizedEmail = normalizeEmail(email);

        if (normalizedEmail == null || normalizedEmail.isBlank()) {
            throw new SQLException("Email address is required.");
        }

        String sql = "INSERT INTO students (email) VALUES (?)";

        try (Connection connection = DBConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, normalizedEmail);
            return statement.executeUpdate() == 1;
        }
    }

    // Find a student's ID by email
    public Long findStudentIdByEmail(String email) throws SQLException {
        String normalizedEmail = normalizeEmail(email);

        if (normalizedEmail == null || normalizedEmail.isBlank()) {
            return null;
        }

        String sql = "SELECT id FROM students WHERE email = ? LIMIT 1";

        try (Connection connection = DBConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, normalizedEmail);

            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    return result.getLong("id");
                }
                return null;
            }
        }
    }

    // Legacy password-based registration method
    public boolean register(
            String fullName,
            String email,
            String mobile,
            String password
    ) throws SQLException, GeneralSecurityException {

        String normalizedEmail = normalizeEmail(email);

        if (normalizedEmail == null || normalizedEmail.isBlank()) {
            throw new SQLException("Email address is required.");
        }

        if (password == null || password.isBlank()) {
            throw new SQLException("Password is required.");
        }

        String passwordHash = PasswordUtil.hash(password);

        String sql = """
                INSERT INTO students
                    (full_name, email, mobile, password_hash)
                VALUES (?, ?, ?, ?)
                """;

        try (Connection connection = DBConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, fullName);
            statement.setString(2, normalizedEmail);
            statement.setString(3, mobile);
            statement.setString(4, passwordHash);

            return statement.executeUpdate() == 1;
        }
    }

    // Legacy password-based login method
    public Student login(
            String email,
            String password
    ) throws SQLException, GeneralSecurityException {

        String normalizedEmail = normalizeEmail(email);

        if (normalizedEmail == null || normalizedEmail.isBlank()
                || password == null) {
            return null;
        }

        String sql = """
                SELECT id, full_name, email, password_hash
                FROM students
                WHERE email = ?
                LIMIT 1
                """;

        try (Connection connection = DBConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, normalizedEmail);

            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }

                String passwordHash = result.getString("password_hash");

                if (passwordHash == null || passwordHash.isBlank()) {
                    return null;
                }

                if (!PasswordUtil.verify(password, passwordHash)) {
                    return null;
                }

                String fullName = result.getString("full_name");

                if (fullName == null || fullName.isBlank()) {
                    fullName = normalizedEmail.substring(
                            0, normalizedEmail.indexOf('@'));
                }

                return new Student(
                        result.getLong("id"),
                        fullName,
                        result.getString("email")
                );
            }
        }
    }
}
