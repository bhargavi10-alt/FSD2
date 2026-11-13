
package com.examtrack.controller;

import com.examtrack.dao.StudentDAO;
import com.examtrack.util.PasswordUtil;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.sql.SQLException;

@WebServlet("/verify-email-otp")
public class VerifyEmailOtpServlet extends HttpServlet {

    private static final int MAX_ATTEMPTS = 5;

    private static final String EMAIL_KEY = "emailOtpEmail";
    private static final String MODE_KEY = "emailOtpMode";
    private static final String HASH_KEY = "emailOtpHash";
    private static final String EXPIRY_KEY = "emailOtpExpires";
    private static final String ATTEMPTS_KEY = "emailOtpAttempts";

    private final StudentDAO studentDAO = new StudentDAO();

    @Override
    protected void doPost(
            HttpServletRequest request,
            HttpServletResponse response
    ) throws ServletException, IOException {

        request.setCharacterEncoding("UTF-8");

        HttpSession session = request.getSession(false);

        if (session == null) {
            response.sendRedirect(
                    request.getContextPath() + "/register.html?error=session");
            return;
        }

        String email = (String) session.getAttribute(EMAIL_KEY);
        String mode = (String) session.getAttribute(MODE_KEY);
        String storedHash = (String) session.getAttribute(HASH_KEY);
        Object expiryObject = session.getAttribute(EXPIRY_KEY);
        Object attemptsObject = session.getAttribute(ATTEMPTS_KEY);

        if (email == null || mode == null || storedHash == null
                || !(expiryObject instanceof Long)) {
            response.sendRedirect(
                    request.getContextPath() + "/register.html?error=session");
            return;
        }

        if (!"register".equals(mode) && !"login".equals(mode)) {
            clearOtpSession(session);
            response.sendRedirect(
                    request.getContextPath() + "/login.html?error=session");
            return;
        }

        long expiry = (Long) expiryObject;
        int attempts = attemptsObject instanceof Integer
                ? (Integer) attemptsObject : 0;

        if (System.currentTimeMillis() > expiry) {
            clearOtpSession(session);
            redirectToMode(request, response, mode, "expired");
            return;
        }

        if (attempts >= MAX_ATTEMPTS) {
            clearOtpSession(session);
            redirectToMode(request, response, mode, "attempts");
            return;
        }

        String otp = request.getParameter("otp");

        if (otp == null || !otp.trim().matches("\\d{6}")) {
            session.setAttribute(ATTEMPTS_KEY, attempts + 1);
            response.sendRedirect(
                    request.getContextPath()
                            + "/verify-otp.html?error=invalid");
            return;
        }

        boolean validOtp;

        try {
            validOtp = PasswordUtil.verify(otp.trim(), storedHash);
        } catch (GeneralSecurityException e) {
            throw new ServletException("Could not verify the email OTP.", e);
        }

        if (!validOtp) {
            int updatedAttempts = attempts + 1;
            session.setAttribute(ATTEMPTS_KEY, updatedAttempts);

            if (updatedAttempts >= MAX_ATTEMPTS) {
                clearOtpSession(session);
                redirectToMode(request, response, mode, "attempts");
                return;
            }

            response.sendRedirect(
                    request.getContextPath()
                            + "/verify-otp.html?error=invalid");
            return;
        }

        try {
            Long studentId;

            if ("register".equals(mode)) {
                // Prevent duplicate registration.
                if (studentDAO.emailExists(email)) {
                    clearOtpSession(session);
                    response.sendRedirect(
                            request.getContextPath()
                                    + "/login.html?error=exists");
                    return;
                }

                // Create the email-only account after OTP verification.
                boolean created = studentDAO.createEmailAccount(email);

                if (!created) {
                    response.sendRedirect(
                            request.getContextPath()
                                    + "/register.html?error=register");
                    return;
                }

                studentId = studentDAO.findStudentIdByEmail(email);

            } else {
                // Login requires an existing account.
                studentId = studentDAO.findStudentIdByEmail(email);

                if (studentId == null) {
                    clearOtpSession(session);
                    response.sendRedirect(
                            request.getContextPath()
                                    + "/register.html?error=notfound");
                    return;
                }
            }

            if (studentId == null) {
                throw new ServletException(
                        "Account verification succeeded, but the student ID "
                                + "could not be retrieved.");
            }

            // Prevent reuse of the pre-authentication session ID.
            request.changeSessionId();

            session.setAttribute("studentId", studentId);
            session.setAttribute("studentEmail", email);

            String studentName = email.substring(0, email.indexOf('@'));
            session.setAttribute("studentName", studentName);

            clearOtpSession(session);

            response.sendRedirect(
                    request.getContextPath() + "/dashboard.html");

        } catch (SQLException e) {
            throw new ServletException(
                    "Database error while verifying the email OTP.", e);
        }
    }

    private void redirectToMode(
            HttpServletRequest request,
            HttpServletResponse response,
            String mode,
            String error
    ) throws IOException {

        String page = "login".equals(mode)
                ? "/login.html" : "/register.html";

        response.sendRedirect(
                request.getContextPath() + page + "?error=" + error);
    }

    private void clearOtpSession(HttpSession session) {
        session.removeAttribute(EMAIL_KEY);
        session.removeAttribute(MODE_KEY);
        session.removeAttribute(HASH_KEY);
        session.removeAttribute(EXPIRY_KEY);
        session.removeAttribute(ATTEMPTS_KEY);
    }
}
