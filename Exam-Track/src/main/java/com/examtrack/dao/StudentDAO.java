
package com.examtrack.dao;

import com.examtrack.model.Student;
import com.examtrack.util.DBConnection;
import com.examtrack.util.PasswordUtil;

import java.security.GeneralSecurityException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Locale;

public class StudentDAO {

    public StudentDAO() {
    }

    // Normalize email addresses consistently.
    private String normalizeEmail(String email) {
        if (email == null) {
            return null;
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    // Check whether an email already exists.
    public boolean emailExists(String email) throws SQLException {
        String normalizedEmail = normalizeEmail(email);

        if (normalizedEmail == null || normalizedEmail.isBlank()) {
            return false;
        }

        String sql = """
                SELECT 1
                FROM students
                WHERE email = ?
                LIMIT 1
                """;

        try (Connection connection = DBConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, normalizedEmail);

            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    // Find a student's database ID using their email.
    public Long findStudentIdByEmail(String email) throws SQLException {
        String normalizedEmail = normalizeEmail(email);

        if (normalizedEmail == null || normalizedEmail.isBlank()) {
            return null;
        }

        String sql = """
                SELECT id
                FROM students
                WHERE email = ?
                LIMIT 1
                """;

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

    // Register a student using a plain-text password.
    // The password is hashed before database storage.
    public boolean register(
            String fullName,
            String email,
            String mobile,
            String password
    ) throws SQLException, GeneralSecurityException {

        if (password == null || password.isBlank()) {
            throw new SQLException("Password is required.");
        }

        String passwordHash = PasswordUtil.hash(password);

        return registerWithHash(
                fullName,
                email,
                mobile,
                passwordHash
        );
    }

    // Register a student when the password is already hashed.
    public boolean registerWithHash(
            String fullName,
            String email,
            String mobile,
            String passwordHash
    ) throws SQLException {

        String normalizedEmail = normalizeEmail(email);

        if (fullName == null || fullName.isBlank()
                || fullName.trim().length() > 100) {
            throw new SQLException("A valid full name is required.");
        }

        if (normalizedEmail == null
                || normalizedEmail.isBlank()
                || normalizedEmail.length() > 150
                || !normalizedEmail.matches(
                    "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")) {
            throw new SQLException("A valid email address is required.");
        }

        if (mobile != null && !mobile.isBlank()
                && mobile.trim().length() > 15) {
            throw new SQLException(
                    "Mobile number must not exceed 15 characters.");
        }

        if (passwordHash == null || passwordHash.isBlank()
                || passwordHash.length() > 255) {
            throw new SQLException("A valid password hash is required.");
        }

        String normalizedName = fullName.trim();
        String normalizedMobile =
                mobile == null || mobile.isBlank()
                        ? null
                        : mobile.trim();

        String sql = """
                INSERT INTO students
                    (full_name, email, mobile, password_hash)
                VALUES (?, ?, ?, ?)
                """;

        try (Connection connection = DBConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, normalizedName);
            statement.setString(2, normalizedEmail);

            if (normalizedMobile == null) {
                statement.setNull(3, Types.VARCHAR);
            } else {
                statement.setString(3, normalizedMobile);
            }

            statement.setString(4, passwordHash);

            return statement.executeUpdate() == 1;
        }
    }

    // Check whether an account exists and has a usable password hash.
    // A missing name does not prevent the account from requesting a login OTP.
    public boolean hasCompleteAccount(String email) throws SQLException {
        String normalizedEmail = normalizeEmail(email);

        if (normalizedEmail == null || normalizedEmail.isBlank()) {
            return false;
        }

        String sql = """
                SELECT 1
                FROM students
                WHERE email = ?
                  AND password_hash IS NOT NULL
                  AND TRIM(password_hash) <> ''
                LIMIT 1
                """;

        try (Connection connection = DBConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, normalizedEmail);

            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    // Authenticate a student using email and password.
    public Student login(
            String email,
            String password
    ) throws SQLException, GeneralSecurityException {

        String normalizedEmail = normalizeEmail(email);

        if (normalizedEmail == null
                || normalizedEmail.isBlank()
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

                // Provide a fallback display name for legacy records.
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
