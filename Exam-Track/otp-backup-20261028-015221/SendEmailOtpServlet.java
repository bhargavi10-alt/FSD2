
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

@WebServlet("/send-email-otp")
public class SendEmailOtpServlet extends HttpServlet {

    private final StudentDAO studentDAO = new StudentDAO();
    private final SecureRandom random = new SecureRandom();

    @Override
    protected void doGet(HttpServletRequest request,
                         HttpServletResponse response) throws IOException {
        response.sendRedirect(request.getContextPath() + "/login.html");
    }

    @Override
    protected void doPost(HttpServletRequest request,
                          HttpServletResponse response)
            throws ServletException, IOException {

        String context = request.getContextPath();
        String email = request.getParameter("email");

        if (email == null || email.isBlank()) {
            response.sendRedirect(context + "/login.html?error=email");
            return;
        }

        email = email.trim().toLowerCase(Locale.ROOT);
        HttpSession session = request.getSession();

        try {
            if (!studentDAO.hasCompleteAccount(email)) {
                response.sendRedirect(context + "/login.html?error=invalid");
                return;
            }

            Long lastSent = (Long) session.getAttribute("emailOtpLastSent");
            String previousEmail =
                    (String) session.getAttribute("emailOtpEmail");
            String previousMode =
                    (String) session.getAttribute("emailOtpMode");

            if (lastSent != null
                    && email.equals(previousEmail)
                    && "login".equals(previousMode)
                    && System.currentTimeMillis() - lastSent < 60_000L) {
                response.sendRedirect(context
                        + "/verify-otp.html?flow=login&error=cooldown");
                return;
            }

            String otp = String.valueOf(100000 + random.nextInt(900000));
            String otpHash = PasswordUtil.hash(otp);

           String studentName = email.substring(0, email.indexOf('@'));
           EmailUtil.sendVerificationCode(email, studentName, otp);

            session.setAttribute("emailOtpEmail", email);
            session.setAttribute("emailOtpMode", "login");
            session.setAttribute("emailOtpHash", otpHash);
            session.setAttribute("emailOtpExpires",
                    System.currentTimeMillis() + 300_000L);
            session.setAttribute("emailOtpAttempts", 0);
            session.setAttribute("emailOtpLastSent",
                    System.currentTimeMillis());

            response.sendRedirect(context + "/verify-otp.html?flow=login");

        } catch (SQLException | GeneralSecurityException e) {
            getServletContext().log("Could not prepare login OTP.", e);
            response.sendRedirect(context + "/login.html?error=server");
        } catch (Exception e) {
            getServletContext().log("Could not send login OTP.", e);
            response.sendRedirect(context + "/login.html?error=email-send");
        }
    }
}
