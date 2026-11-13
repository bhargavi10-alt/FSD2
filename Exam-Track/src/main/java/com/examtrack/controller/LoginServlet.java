
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

@WebServlet("/login")
public class LoginServlet extends HttpServlet {

    private final StudentDAO studentDAO = new StudentDAO();

    @Override
    protected void doPost(
            HttpServletRequest request,
            HttpServletResponse response)
            throws ServletException, IOException {

        request.setCharacterEncoding("UTF-8");

        String email = request.getParameter("email");
        String password = request.getParameter("password");

        if (email == null || email.isBlank()
                || password == null || password.isBlank()) {
            response.sendRedirect("login.html?error=missing");
            return;
        }

        email = email.trim().toLowerCase();

        try {
            Student student = studentDAO.login(email, password);

            if (student == null) {
                response.sendRedirect("login.html?error=invalid");
                return;
            }

            HttpSession oldSession = request.getSession(false);

            if (oldSession != null) {
                oldSession.invalidate();
            }

            HttpSession session = request.getSession(true);
            session.setAttribute("studentId", student.getId());
            session.setAttribute("studentName", student.getFullName());
            session.setAttribute("studentEmail", student.getEmail());
            session.setMaxInactiveInterval(30 * 60);

            response.sendRedirect("dashboard.html");

        } catch (Exception e) {
            getServletContext().log("ExamTrack login failed.", e);
            response.sendRedirect("login.html?error=server");
        }
    }

    @Override
    protected void doGet(
            HttpServletRequest request,
            HttpServletResponse response)
            throws ServletException, IOException {

        response.sendRedirect("login.html");
    }
}
