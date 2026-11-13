
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

@WebServlet("/send-registration-otp")
public class SendRegistrationOtpServlet extends HttpServlet {

    private final StudentDAO studentDAO = new StudentDAO();
    private final SecureRandom random = new SecureRandom();

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    private static final Pattern MOBILE_PATTERN =
            Pattern.compile("^[0-9]{10,15}$");

    private boolean strongPassword(String password) {
        return password != null
                && password.length() >= 8
                && password.length() <= 128
                && password.matches(".*[A-Z].*")
                && password.matches(".*[a-z].*")
                && password.matches(".*[0-9].*")
                && password.matches(".*[^A-Za-z0-9].*");
    }

    @Override
    protected void doGet(HttpServletRequest request,
                         HttpServletResponse response) throws IOException {
        response.sendRedirect(request.getContextPath() + "/register.html");
    }

    @Override
    protected void doPost(HttpServletRequest request,
                          HttpServletResponse response)
            throws ServletException, IOException {

        request.setCharacterEncoding("UTF-8");
        String context = request.getContextPath();

        String fullName = request.getParameter("fullName");
        String email = request.getParameter("email");
        String mobile = request.getParameter("mobile");
        String password = request.getParameter("password");
        String confirmPassword = request.getParameter("confirmPassword");

        if (fullName == null || fullName.isBlank()
                || fullName.trim().length() > 100) {
            response.sendRedirect(context + "/register.html?error=name");
            return;
        }

        if (email == null || email.isBlank()) {
            response.sendRedirect(context + "/register.html?error=email");
            return;
        }

        email = email.trim().toLowerCase(Locale.ROOT);

        if (email.length() > 150 || !EMAIL_PATTERN.matcher(email).matches()) {
            response.sendRedirect(context + "/register.html?error=email");
            return;
        }

        if (mobile != null && !mobile.isBlank()) {
            mobile = mobile.trim();
            if (!MOBILE_PATTERN.matcher(mobile).matches()) {
                response.sendRedirect(context + "/register.html?error=mobile");
                return;
            }
        } else {
            mobile = null;
        }

        if (!strongPassword(password)) {
            response.sendRedirect(context + "/register.html?error=password");
            return;
        }

        if (confirmPassword == null || !password.equals(confirmPassword)) {
            response.sendRedirect(context + "/register.html?error=confirm");
            return;
        }

        HttpSession session = request.getSession();

        try {
            if (studentDAO.emailExists(email)) {
                response.sendRedirect(context + "/register.html?error=exists");
                return;
            }

            Long lastSent =
                    (Long) session.getAttribute("registrationOtpLastSent");
            String previousEmail =
                    (String) session.getAttribute("registrationOtpEmail");

            if (lastSent != null && email.equals(previousEmail)
                    && System.currentTimeMillis() - lastSent < 60_000L) {
                response.sendRedirect(context + "/register.html?error=cooldown");
                return;
            }

            String passwordHash = PasswordUtil.hash(password);
            String otp = String.valueOf(100000 + random.nextInt(900000));
            String otpHash = PasswordUtil.hash(otp);

            EmailUtil.sendVerificationCode(email, otp, "registration");

            session.setAttribute("pendingFullName", fullName.trim());
            session.setAttribute("pendingEmail", email);
            session.setAttribute("pendingMobile", mobile);
            session.setAttribute("pendingPasswordHash", passwordHash);
            session.removeAttribute("pendingPassword");

            session.setAttribute("registrationOtpHash", otpHash);
            session.setAttribute("registrationOtpExpiresAt",
                    System.currentTimeMillis() + 300_000L);
            session.setAttribute("registrationOtpAttempts", 0);
            session.setAttribute("registrationOtpEmail", email);
            session.setAttribute("registrationOtpLastSent",
                    System.currentTimeMillis());

            response.sendRedirect(context + "/verify-otp.html?flow=register");

        } catch (SQLException | GeneralSecurityException e) {
            getServletContext().log("Registration preparation failed.", e);
            response.sendRedirect(context + "/register.html?error=server");
        } catch (Exception e) {
            getServletContext().log("Registration OTP email failed.", e);
            response.sendRedirect(context + "/register.html?error=email-send");
        }
    }
}
