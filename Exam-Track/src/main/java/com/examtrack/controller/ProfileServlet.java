
package com.examtrack.controller;

import com.examtrack.util.DBConnection;
import com.google.gson.Gson;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

@WebServlet("/profile")
public class ProfileServlet extends HttpServlet {

    private final Gson gson = new Gson();

    @Override
    protected void doGet(HttpServletRequest request,
                         HttpServletResponse response) throws IOException {

        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        HttpSession session = request.getSession(false);
        Object idValue = session == null ? null
                : session.getAttribute("studentId");

        if (!(idValue instanceof Number)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.getWriter().write(gson.toJson(Map.of(
                    "success", false,
                    "message", "Please log in to view your profile."
            )));
            return;
        }

        long studentId = ((Number) idValue).longValue();

        try (Connection connection = DBConnection.getConnection()) {
            Map<String, Object> result = new LinkedHashMap<>();
            Map<String, Object> student = new LinkedHashMap<>();
            Map<String, Object> stats = new LinkedHashMap<>();

            String studentSql = """
                    SELECT id, full_name, email, mobile, created_at
                    FROM students
                    WHERE id = ?
                    """;

            try (PreparedStatement ps =
                         connection.prepareStatement(studentSql)) {
                ps.setLong(1, studentId);

                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        response.setStatus(
                                HttpServletResponse.SC_UNAUTHORIZED);
                        response.getWriter().write(gson.toJson(Map.of(
                                "success", false,
                                "message", "Student account was not found."
                        )));
                        return;
                    }

                    student.put("id", rs.getLong("id"));
                    student.put("fullName", rs.getString("full_name"));
                    student.put("email", rs.getString("email"));
                    student.put("mobile", rs.getString("mobile"));

                    var createdAt = rs.getTimestamp("created_at");
                    student.put("createdAt",
                            createdAt == null ? null : createdAt.toString());
                }
            }

            String statsSql = """
                    SELECT COUNT(*) AS total_exams,
                           COALESCE(AVG(total_score), 0) AS average_score,
                           COALESCE(MAX(total_score), 0) AS best_score,
                           COALESCE(SUM(aptitude_score), 0) AS aptitude_total,
                           COALESCE(SUM(coding_score), 0) AS coding_total,
                           COUNT(DISTINCT DATE(submitted_at)) AS active_days
                    FROM exam_results
                    WHERE student_id = ?
                    """;

            try (PreparedStatement ps =
                         connection.prepareStatement(statsSql)) {
                ps.setLong(1, studentId);

                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    stats.put("totalExams", rs.getInt("total_exams"));
                    stats.put("averageScore", rs.getBigDecimal("average_score"));
                    stats.put("bestScore", rs.getBigDecimal("best_score"));
                    stats.put("aptitudeTotal", rs.getBigDecimal("aptitude_total"));
                    stats.put("codingTotal", rs.getBigDecimal("coding_total"));
                    stats.put("activeDays", rs.getInt("active_days"));
                }
            }

            ArrayList<Map<String, Object>> strengths = new ArrayList<>();

            String strengthSql = """
                    SELECT q.category,
                           COUNT(*) AS answered,
                           SUM(a.is_correct) AS correct_answers
                    FROM aptitude_answers a
                    JOIN exam_attempt_questions aq
                        ON aq.id = a.attempt_question_id
                    JOIN aptitude_questions q
                        ON q.id = aq.aptitude_question_id
                    JOIN exam_attempts ea
                        ON ea.id = aq.attempt_id
                    WHERE ea.student_id = ?
                    GROUP BY q.category
                    ORDER BY
                        (SUM(a.is_correct) * 1.0 / COUNT(*)) DESC,
                        COUNT(*) DESC
                    """;

            try (PreparedStatement ps =
                         connection.prepareStatement(strengthSql)) {
                ps.setLong(1, studentId);

                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> item = new LinkedHashMap<>();
                        int answered = rs.getInt("answered");
                        int correct = rs.getInt("correct_answers");

                        item.put("category", rs.getString("category"));
                        item.put("answered", answered);
                        item.put("correct", correct);
                        item.put("accuracy", answered == 0 ? 0
                                : Math.round(correct * 100.0 / answered));

                        strengths.add(item);
                    }
                }
            }

            ArrayList<Map<String, Object>> history = new ArrayList<>();

            String historySql = """
                    SELECT exam_type, aptitude_score, coding_score,
                           total_score, submitted_at
                    FROM exam_results
                    WHERE student_id = ?
                    ORDER BY submitted_at DESC
                    LIMIT 10
                    """;

            try (PreparedStatement ps =
                         connection.prepareStatement(historySql)) {
                ps.setLong(1, studentId);

                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> item = new LinkedHashMap<>();

                        item.put("examType", rs.getString("exam_type"));
                        item.put("aptitudeScore",
                                rs.getBigDecimal("aptitude_score"));
                        item.put("codingScore",
                                rs.getBigDecimal("coding_score"));
                        item.put("totalScore",
                                rs.getBigDecimal("total_score"));

                        var submittedAt = rs.getTimestamp("submitted_at");
                        item.put("submittedAt", submittedAt == null
                                ? null : submittedAt.toString());

                        history.add(item);
                    }
                }
            }

            result.put("success", true);
            result.put("student", student);
            result.put("stats", stats);
            result.put("strengths", strengths);
            result.put("history", history);

            response.getWriter().write(gson.toJson(result));

        } catch (Exception e) {
            getServletContext().log("ExamTrack profile request failed.", e);
            response.setStatus(
                    HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.getWriter().write(gson.toJson(Map.of(
                    "success", false,
                    "message", "Could not load profile. Check the server log."
            )));
        }
    }
}
