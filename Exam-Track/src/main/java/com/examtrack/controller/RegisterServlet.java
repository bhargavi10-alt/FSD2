
package com.examtrack.controller;

import com.examtrack.dao.StudentDAO;
import com.examtrack.model.Student;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;
import java.util.Locale;
import java.util.regex.Pattern;

@WebServlet("/register")
public class RegisterServlet extends HttpServlet {

    private final StudentDAO studentDAO = new StudentDAO();

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile(
                    "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$"
            );

    private static final Pattern MOBILE_PATTERN =
            Pattern.compile("[0-9+() -]{7,15}");

    private static boolean validPassword(String password) {
        return password != null
                && password.length() >= 8
                && password.length() <= 128
                && password.matches(".*[A-Z].*")
                && password.matches(".*[a-z].*")
                && password.matches(".*[0-9].*")
                && password.matches(".*[^A-Za-z0-9].*");
    }

    @Override
    protected void doGet(
            HttpServletRequest request,
            HttpServletResponse response)
            throws ServletException, IOException {

        response.sendRedirect(
                request.getContextPath() + "/register.html"
        );
    }

    @Override
    protected void doPost(
            HttpServletRequest request,
            HttpServletResponse response)
            throws ServletException, IOException {

        request.setCharacterEncoding("UTF-8");

        String fullName = request.getParameter("fullName");
        String email = request.getParameter("email");
        String mobile = request.getParameter("mobile");
        String password = request.getParameter("password");

        // Validate registration details. Password is entered only once.
        if (fullName == null
                || fullName.isBlank()
                || fullName.trim().length() < 2
                || fullName.trim().length() > 100
                || email == null
                || email.trim().length() > 150
                || !EMAIL_PATTERN.matcher(email.trim()).matches()
                || (mobile != null
                    && !mobile.isBlank()
                    && !MOBILE_PATTERN.matcher(mobile.trim()).matches())
                || !validPassword(password)) {

            response.sendRedirect(
                    request.getContextPath()
                            + "/register.html?error=invalid"
            );
            return;
        }

        fullName = fullName.trim();
        email = email.trim().toLowerCase(Locale.ROOT);
        mobile = mobile == null || mobile.isBlank()
                ? null
                : mobile.trim();

        try {
            // Do not create a duplicate account for the same email.
            if (studentDAO.emailExists(email)) {
                response.sendRedirect(
                        request.getContextPath()
                                + "/register.html?error=exists"
                );
                return;
            }

            // StudentDAO.register should hash the password before saving it.
            boolean created = studentDAO.register(
                    fullName,
                    email,
                    mobile,
                    password
            );

            if (!created) {
                response.sendRedirect(
                        request.getContextPath()
                                + "/register.html?error=server"
                );
                return;
            }

            // Log in the newly registered student automatically.
            Student student = studentDAO.login(email, password);

            if (student == null) {
                response.sendRedirect(
                        request.getContextPath()
                                + "/login.html?registered=1"
                );
                return;
            }

            // Replace any existing session to prevent session reuse.
            HttpSession oldSession = request.getSession(false);
            if (oldSession != null) {
                oldSession.invalidate();
            }

            HttpSession session = request.getSession(true);
            session.setAttribute("studentId", student.getId());
            session.setAttribute("studentName", student.getFullName());
            session.setAttribute("studentEmail", student.getEmail());
            session.setMaxInactiveInterval(30 * 60);

            // Registration successful: open the dashboard directly.
            response.sendRedirect(
                    request.getContextPath() + "/dashboard.html"
            );

        } catch (Exception e) {
            getServletContext().log(
                    "ExamTrack registration failed.", e
            );

            response.sendRedirect(
                    request.getContextPath()
                            + "/register.html?error=server"
            );
        }
    }
}
