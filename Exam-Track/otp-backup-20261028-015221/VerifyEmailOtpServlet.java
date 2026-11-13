
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
    protected void doGet(
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        response.sendRedirect(
                request.getContextPath() + "/verify-otp.html");
    }

    @Override
    protected void doPost(
            HttpServletRequest request,
            HttpServletResponse response
    ) throws ServletException, IOException {

        request.setCharacterEncoding("UTF-8");

        HttpSession session = request.getSession(false);

        if (session == null) {
            redirect(request, response, "/login.html", "session");
            return;
        }

        String email = (String) session.getAttribute(EMAIL_KEY);
        String mode = (String) session.getAttribute(MODE_KEY);
        String storedHash = (String) session.getAttribute(HASH_KEY);

        Object expiryObject = session.getAttribute(EXPIRY_KEY);
        Object attemptsObject = session.getAttribute(ATTEMPTS_KEY);

        // This endpoint is for existing-account OTP login only.
        // Registration must use the complete registration OTP flow.
        if (!"login".equals(mode)) {
            clearOtpSession(session);
            redirect(request, response, "/register.html", "verify");
            return;
        }

        if (email == null || email.isBlank()
                || storedHash == null || storedHash.isBlank()
                || !(expiryObject instanceof Number)) {
            clearOtpSession(session);
            redirect(request, response, "/login.html", "session");
            return;
        }

        long expiry = ((Number) expiryObject).longValue();

        int attempts = attemptsObject instanceof Number
                ? ((Number) attemptsObject).intValue() : 0;

        if (System.currentTimeMillis() > expiry) {
            clearOtpSession(session);
            redirect(request, response, "/login.html", "expired");
            return;
        }

        if (attempts >= MAX_ATTEMPTS) {
            clearOtpSession(session);
            redirect(request, response, "/login.html", "attempts");
            return;
        }

        String otp = request.getParameter("otp");

        if (otp == null || !otp.trim().matches("\\d{6}")) {
            recordFailedAttempt(session, attempts);

            if (attempts + 1 >= MAX_ATTEMPTS) {
                clearOtpSession(session);
                redirect(request, response, "/login.html", "attempts");
            } else {
                redirect(request, response, "/verify-otp.html", "invalid");
            }
            return;
        }

        try {
            if (!PasswordUtil.verify(otp.trim(), storedHash)) {
                recordFailedAttempt(session, attempts);

                if (attempts + 1 >= MAX_ATTEMPTS) {
                    clearOtpSession(session);
                    redirect(request, response, "/login.html", "attempts");
                } else {
                    redirect(request, response, "/verify-otp.html", "invalid");
                }
                return;
            }

            Long studentId = studentDAO.findStudentIdByEmail(email);

            if (studentId == null) {
                clearOtpSession(session);
                redirect(request, response, "/register.html", "notfound");
                return;
            }

            // OTP login must not authenticate incomplete accounts.
            // Such accounts should complete the proper registration process.
            if (!studentDAO.hasCompleteAccount(email)) {
                clearOtpSession(session);
                redirect(request, response, "/register.html", "incomplete");
                return;
            }

            // Prevent session fixation.
            request.changeSessionId();

            session.setAttribute("studentId", studentId);
            session.setAttribute("studentEmail", email);

            String studentName = email.substring(0, email.indexOf('@'));
            session.setAttribute("studentName", studentName);

            clearOtpSession(session);

            response.sendRedirect(
                    request.getContextPath() + "/dashboard.html");

        } catch (GeneralSecurityException e) {
            throw new ServletException(
                    "Could not verify the email OTP.", e);
        } catch (SQLException e) {
            throw new ServletException(
                    "Database error while verifying the email OTP.", e);
        }
    }

    private void recordFailedAttempt(HttpSession session, int attempts) {
        session.setAttribute(ATTEMPTS_KEY, attempts + 1);
    }

    private void redirect(
            HttpServletRequest request,
            HttpServletResponse response,
            String page,
            String error
    ) throws IOException {
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
