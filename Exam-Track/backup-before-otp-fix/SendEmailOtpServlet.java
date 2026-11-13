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
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.Locale;
import java.util.regex.Pattern;

@WebServlet("/send-email-otp")
public class SendEmailOtpServlet extends HttpServlet {

    private static final long OTP_VALIDITY_MS = 5 * 60 * 1000L;
    private static final long RESEND_COOLDOWN_MS = 60 * 1000L;

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    private static final SecureRandom RANDOM = new SecureRandom();

    private final StudentDAO studentDAO = new StudentDAO();

    @Override
    protected void doPost(
            HttpServletRequest request,
            HttpServletResponse response
    ) throws ServletException, IOException {

        request.setCharacterEncoding("UTF-8");

        String context = request.getContextPath();
        String email = request.getParameter("email");
        String mode = request.getParameter("mode");

        if (email == null || mode == null) {
            response.sendRedirect(context + "/register.html?error=invalid");
            return;
        }

        email = email.trim().toLowerCase(Locale.ROOT);
        mode = mode.trim().toLowerCase(Locale.ROOT);

        if (!EMAIL_PATTERN.matcher(email).matches()) {
            redirectToPage(request, response, mode, "invalid");
            return;
        }

        if (!"register".equals(mode) && !"login".equals(mode)) {
            response.sendRedirect(context + "/register.html?error=invalid");
            return;
        }

        HttpSession session = request.getSession();

        // Prevent repeated OTP emails during the cooldown period.
        Object lastSentObject = session.getAttribute("emailOtpLastSent");
        Object previousEmailObject = session.getAttribute("emailOtpEmail");
        Object previousModeObject = session.getAttribute("emailOtpMode");

        if (lastSentObject instanceof Long
                && email.equals(previousEmailObject)
                && mode.equals(previousModeObject)) {

            long lastSent = (Long) lastSentObject;

            if (System.currentTimeMillis() - lastSent < RESEND_COOLDOWN_MS) {
                response.sendRedirect(
                        context + "/verify-otp.html?error=cooldown");
                return;
            }
        }

        try {
            boolean exists = studentDAO.emailExists(email);

            if ("register".equals(mode) && exists) {
                response.sendRedirect(context + "/login.html?error=exists");
                return;
            }

            if ("login".equals(mode) && !exists) {
                response.sendRedirect(context + "/register.html?error=notfound");
                return;
            }

            // Generate a cryptographically secure six-digit OTP.
            String otp = String.format(
                    Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));

            String otpHash = PasswordUtil.hash(otp);

            // EmailUtil uses EXAMTRACK_MAIL_USERNAME and
            // EXAMTRACK_MAIL_APP_PASSWORD from environment variables.
            String studentName = email.substring(0, email.indexOf('@'));

            EmailUtil.sendVerificationCode(email, studentName, otp);

            // Store verification state only after the email send succeeds.
            session.setAttribute("emailOtpEmail", email);
            session.setAttribute("emailOtpMode", mode);
            session.setAttribute("emailOtpHash", otpHash);
            session.setAttribute(
                    "emailOtpExpires",
                    System.currentTimeMillis() + OTP_VALIDITY_MS);
            session.setAttribute("emailOtpAttempts", 0);
            session.setAttribute("emailOtpLastSent", System.currentTimeMillis());

            response.sendRedirect(context + "/verify-otp.html");

        } catch (SQLException e) {
            getServletContext().log(
                    "Database error while requesting an ExamTrack email OTP.", e);

            response.sendRedirect(
                    context + "/" + pageFor(mode) + "?error=database");

        } catch (GeneralSecurityException e) {
            getServletContext().log(
                    "Could not secure the ExamTrack email OTP.", e);

            response.sendRedirect(
                    context + "/" + pageFor(mode) + "?error=otp");

        } catch (Exception e) {
            getServletContext().log(
                    "Could not send the ExamTrack email OTP.", e);

            response.sendRedirect(
                    context + "/" + pageFor(mode) + "?error=mail");
        }
    }

    private void redirectToPage(
            HttpServletRequest request,
            HttpServletResponse response,
            String mode,
            String error
    ) throws IOException {

        response.sendRedirect(
                request.getContextPath() + "/" + pageFor(mode) + "?error=" + error);
    }

    private String pageFor(String mode) {
        return "login".equals(mode) ? "login.html" : "register.html";
    }
}
