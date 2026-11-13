
package com.examtrack.controller;

import com.examtrack.dao.StudentDAO;
import com.examtrack.util.EmailUtil;
import com.examtrack.util.PasswordUtil;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.regex.Pattern;

@WebServlet("/send-registration-otp")
public class SendRegistrationOtpServlet extends HttpServlet {

    private final StudentDAO studentDAO = new StudentDAO();
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final long OTP_VALIDITY_MS = 5 * 60 * 1000L;
    private static final long RESEND_WAIT_MS = 60 * 1000L;

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile(
                    "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$"
            );

    @Override
    protected void doPost(
            HttpServletRequest request,
            HttpServletResponse response)
            throws ServletException, IOException {

        request.setCharacterEncoding("UTF-8");

        String fullName = trim(request.getParameter("fullName"));
        String email = trim(request.getParameter("email"))
                .toLowerCase(Locale.ROOT);
        String mobile = trim(request.getParameter("mobile"));
        String password = request.getParameter("password");
        String confirmPassword = request.getParameter("confirmPassword");

        if (fullName.isBlank() || email.isBlank()
                || password == null || confirmPassword == null) {
            redirectError(response, "missing");
            return;
        }

        if (fullName.length() > 100) {
            redirectError(response, "name");
            return;
        }

        if (email.length() > 150
                || !EMAIL_PATTERN.matcher(email).matches()) {
            redirectError(response, "email");
            return;
        }

        if (mobile.length() > 15) {
            redirectError(response, "mobile");
            return;
        }

        if (password.length() < 8 || password.length() > 128) {
            redirectError(response, "password");
            return;
        }

        if (!password.equals(confirmPassword)) {
            redirectError(response, "confirm");
            return;
        }

        HttpSession session = request.getSession(true);

        // Limit repeated OTP emails from the same session.
        Long previousSend = (Long) session.getAttribute("otpLastSentAt");
        long now = System.currentTimeMillis();

        if (previousSend != null
                && now - previousSend < RESEND_WAIT_MS) {
            response.sendRedirect("verify-otp.html?error=wait");
            return;
        }

        try {
            if (studentDAO.emailExists(email)) {
                clearPendingRegistration(session);
                response.sendRedirect("register.html?error=exists");
                return;
            }

            String otp = String.format(
                    Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));

            // Hash the OTP rather than storing the plain code.
            String otpHash = PasswordUtil.hash(otp);

            // Send first. Do not retain a usable OTP if delivery fails.
            EmailUtil.sendVerificationCode(email, fullName, otp);

            session.setAttribute("pendingFullName", fullName);
            session.setAttribute("pendingEmail", email);
            session.setAttribute("pendingMobile", mobile);
            session.setAttribute("pendingPassword", password);
            session.setAttribute("registrationOtpHash", otpHash);
            session.setAttribute(
                    "registrationOtpExpiresAt",
                    System.currentTimeMillis() + OTP_VALIDITY_MS);
            session.setAttribute("registrationOtpAttempts", 0);
            session.setAttribute("otpLastSentAt", System.currentTimeMillis());

            response.sendRedirect("verify-otp.html?sent=1");

        } catch (Exception e) {
            clearPendingRegistration(session);
            getServletContext().log(
                    "Could not send ExamTrack registration OTP.", e);
            response.sendRedirect("register.html?error=mail");
        }
    }

    @Override
    protected void doGet(
            HttpServletRequest request,
            HttpServletResponse response)
            throws ServletException, IOException {

        response.sendRedirect("register.html");
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static void redirectError(
            HttpServletResponse response,
            String error) throws IOException {

        response.sendRedirect("register.html?error=" + error);
    }

    private static void clearPendingRegistration(HttpSession session) {
        session.removeAttribute("pendingFullName");
        session.removeAttribute("pendingEmail");
        session.removeAttribute("pendingMobile");
        session.removeAttribute("pendingPassword");
        session.removeAttribute("registrationOtpHash");
        session.removeAttribute("registrationOtpExpiresAt");
        session.removeAttribute("registrationOtpAttempts");
    }
}
