
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

@WebServlet("/verify-registration-otp")
public class VerifyRegistrationOtpServlet extends HttpServlet {

    private final StudentDAO studentDAO = new StudentDAO();

    @Override
    protected void doGet(HttpServletRequest request,
                         HttpServletResponse response) throws IOException {
        response.sendRedirect(request.getContextPath()
                + "/verify-otp.html?flow=register");
    }

    @Override
    protected void doPost(HttpServletRequest request,
                          HttpServletResponse response)
            throws ServletException, IOException {

        String context = request.getContextPath();
        HttpSession session = request.getSession(false);

        if (session == null) {
            response.sendRedirect(context + "/register.html?error=session");
            return;
        }

        String fullName = (String) session.getAttribute("pendingFullName");
        String email = (String) session.getAttribute("pendingEmail");
        String mobile = (String) session.getAttribute("pendingMobile");
        String passwordHash =
                (String) session.getAttribute("pendingPasswordHash");
        String savedOtpHash =
                (String) session.getAttribute("registrationOtpHash");
        String enteredOtp = request.getParameter("otp");

        Long expiresAt =
                (Long) session.getAttribute("registrationOtpExpiresAt");
        Integer attempts =
                (Integer) session.getAttribute("registrationOtpAttempts");

        if (attempts == null) attempts = 0;

        if (fullName == null || email == null || passwordHash == null
                || savedOtpHash == null || expiresAt == null) {
            response.sendRedirect(context + "/register.html?error=session");
            return;
        }

        if (System.currentTimeMillis() > expiresAt) {
            clearPendingRegistration(session);
            response.sendRedirect(context
                    + "/register.html?error=expired");
            return;
        }

        if (attempts >= 5) {
            clearPendingRegistration(session);
            response.sendRedirect(context
                    + "/register.html?error=attempts");
            return;
        }

        if (enteredOtp == null || !enteredOtp.matches("[0-9]{6}")) {
            session.setAttribute("registrationOtpAttempts", attempts + 1);
            response.sendRedirect(context
                    + "/verify-otp.html?flow=register&error=invalid");
            return;
        }

        try {
            if (!PasswordUtil.verify(enteredOtp, savedOtpHash)) {
                int newAttempts = attempts + 1;
                session.setAttribute("registrationOtpAttempts", newAttempts);

                if (newAttempts >= 5) {
                    clearPendingRegistration(session);
                    response.sendRedirect(context
                            + "/register.html?error=attempts");
                } else {
                    response.sendRedirect(context
                            + "/verify-otp.html?flow=register&error=invalid");
                }
                return;
            }

            if (studentDAO.emailExists(email)) {
                clearPendingRegistration(session);
                response.sendRedirect(context + "/register.html?error=exists");
                return;
            }

            boolean registered = studentDAO.registerWithHash(
                    fullName, email, mobile, passwordHash);

            if (!registered) {
                response.sendRedirect(context + "/register.html?error=server");
                return;
            }

            clearPendingRegistration(session);
            response.sendRedirect(context + "/login.html?registered=success");

        } catch (SQLException | GeneralSecurityException e) {
            getServletContext().log("Registration OTP verification failed.", e);
            response.sendRedirect(context + "/register.html?error=server");
        }
    }

    private void clearPendingRegistration(HttpSession session) {
        session.removeAttribute("pendingFullName");
        session.removeAttribute("pendingEmail");
        session.removeAttribute("pendingMobile");
        session.removeAttribute("pendingPassword");
        session.removeAttribute("pendingPasswordHash");
        session.removeAttribute("registrationOtpHash");
        session.removeAttribute("registrationOtpExpiresAt");
        session.removeAttribute("registrationOtpAttempts");
        session.removeAttribute("registrationOtpEmail");
        session.removeAttribute("registrationOtpLastSent");
    }
}
