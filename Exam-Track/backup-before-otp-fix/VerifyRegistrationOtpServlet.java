
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

@WebServlet("/verify-registration-otp")
public class VerifyRegistrationOtpServlet extends HttpServlet {

    private final StudentDAO studentDAO = new StudentDAO();

    private static final int MAX_ATTEMPTS = 5;

    @Override
    protected void doPost(
            HttpServletRequest request,
            HttpServletResponse response)
            throws ServletException, IOException {

        request.setCharacterEncoding("UTF-8");

        HttpSession session = request.getSession(false);

        if (session == null) {
            response.sendRedirect("register.html?error=session");
            return;
        }

        String otp = request.getParameter("otp");
        String otpHash =
                (String) session.getAttribute("registrationOtpHash");

        Long expiresAt =
                (Long) session.getAttribute("registrationOtpExpiresAt");

        Integer attempts =
                (Integer) session.getAttribute("registrationOtpAttempts");

        String fullName =
                (String) session.getAttribute("pendingFullName");
        String email =
                (String) session.getAttribute("pendingEmail");
        String mobile =
                (String) session.getAttribute("pendingMobile");
        String password =
                (String) session.getAttribute("pendingPassword");

        if (otpHash == null || expiresAt == null
                || fullName == null || email == null
                || password == null) {
            clearPendingRegistration(session);
            response.sendRedirect("register.html?error=session");
            return;
        }

        if (System.currentTimeMillis() >= expiresAt) {
            clearPendingRegistration(session);
            response.sendRedirect("verify-otp.html?error=expired");
            return;
        }

        if (attempts == null) {
            attempts = 0;
        }

        if (attempts >= MAX_ATTEMPTS) {
            clearPendingRegistration(session);
            response.sendRedirect("register.html?error=attempts");
            return;
        }

        if (otp == null || !otp.matches("\\d{6}")) {
            handleIncorrectOtp(session, attempts, response);
            return;
        }

        try {
            boolean valid = PasswordUtil.verify(otp, otpHash);

            if (!valid) {
                handleIncorrectOtp(session, attempts, response);
                return;
            }

            // Check again in case another registration used this email.
            if (studentDAO.emailExists(email)) {
                clearPendingRegistration(session);
                response.sendRedirect("register.html?error=exists");
                return;
            }

            boolean registered = studentDAO.register(
                    fullName, email, mobile, password);

            clearPendingRegistration(session);

            if (registered) {
                response.sendRedirect("login.html?registered=1");
            } else {
                response.sendRedirect("register.html?error=exists");
            }

        } catch (GeneralSecurityException e) {
            getServletContext().log(
                    "OTP verification security error.", e);
            response.sendRedirect("verify-otp.html?error=server");

        } catch (Exception e) {
            getServletContext().log(
                    "OTP verification or registration failed.", e);
            response.sendRedirect("verify-otp.html?error=server");
        }
    }

    private void handleIncorrectOtp(
            HttpSession session,
            int attempts,
            HttpServletResponse response) throws IOException {

        attempts++;
        session.setAttribute("registrationOtpAttempts", attempts);

        if (attempts >= MAX_ATTEMPTS) {
            clearPendingRegistration(session);
            response.sendRedirect("register.html?error=attempts");
        } else {
            response.sendRedirect("verify-otp.html?error=invalid");
        }
    }

    private void clearPendingRegistration(HttpSession session) {
        session.removeAttribute("pendingFullName");
        session.removeAttribute("pendingEmail");
        session.removeAttribute("pendingMobile");
        session.removeAttribute("pendingPassword");
        session.removeAttribute("registrationOtpHash");
        session.removeAttribute("registrationOtpExpiresAt");
        session.removeAttribute("registrationOtpAttempts");
        session.removeAttribute("otpLastSentAt");
    }

    @Override
    protected void doGet(
            HttpServletRequest request,
            HttpServletResponse response)
            throws ServletException, IOException {

        response.sendRedirect("verify-otp.html");
    }
}
