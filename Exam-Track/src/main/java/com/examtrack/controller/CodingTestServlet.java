
package com.examtrack.controller;

import com.examtrack.util.DBConnection;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Locale;
import java.util.Set;

@WebServlet("/coding-test/*")
public class CodingTestServlet extends HttpServlet {

    private static final Set<String> LANGUAGES =
            Set.of("C", "CPP", "JAVA", "PYTHON");

    @Override
    protected void doGet(HttpServletRequest request,
                         HttpServletResponse response) throws IOException {
        prepare(response);

        Long studentId = getStudentId(request);
        if (studentId == null) {
            sendError(response, 401, "Please log in first.");
            return;
        }

        if (!"/questions".equals(request.getPathInfo())) {
            sendError(response, 404, "Use /coding-test/questions.");
            return;
        }

        String sql = """
            SELECT id, title, problem_statement, input_format,
                   output_format, constraints_text, sample_input,
                   sample_output, category, difficulty, marks
            FROM coding_questions
            WHERE is_active = 1
            ORDER BY id
            """;

        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            com.google.gson.JsonArray questions =
                    new com.google.gson.JsonArray();

            while (rs.next()) {
                JsonObject q = new JsonObject();
                q.addProperty("id", rs.getLong("id"));
                q.addProperty("title", rs.getString("title"));
                q.addProperty("problemStatement",
                        rs.getString("problem_statement"));
                q.addProperty("inputFormat", rs.getString("input_format"));
                q.addProperty("outputFormat", rs.getString("output_format"));
                q.addProperty("constraints",
                        rs.getString("constraints_text"));
                q.addProperty("sampleInput", rs.getString("sample_input"));
                q.addProperty("sampleOutput", rs.getString("sample_output"));
                q.addProperty("category", rs.getString("category"));
                q.addProperty("difficulty", rs.getString("difficulty"));
                q.addProperty("marks", rs.getBigDecimal("marks"));
                questions.add(q);
            }

            JsonObject result = new JsonObject();
            result.addProperty("success", true);
            result.add("questions", questions);
            sendJson(response, 200, result);

        } catch (Exception e) {
            getServletContext().log("Unable to load coding questions", e);
            sendError(response, 500, "Unable to load coding questions.");
        }
    }

    @Override
    protected void doPost(HttpServletRequest request,
                          HttpServletResponse response) throws IOException {
        prepare(response);

        Long studentId = getStudentId(request);
        if (studentId == null) {
            sendError(response, 401, "Please log in before submitting code.");
            return;
        }

        if (!"/submit".equals(request.getPathInfo())) {
            sendError(response, 404, "Use /coding-test/submit.");
            return;
        }

        JsonObject body;
        try {
            body = JsonParser.parseString(
                    request.getReader().lines()
                            .reduce("", (a, b) -> a + b)
            ).getAsJsonObject();
        } catch (Exception e) {
            sendError(response, 400, "Invalid JSON request.");
            return;
        }

        try {
            if (!body.has("questionId")
                    || !body.has("language")
                    || !body.has("sourceCode")
                    || !body.has("section")) {
                sendError(response, 400,
                        "Question, language, code and section are required.");
                return;
            }

            long questionId = body.get("questionId").getAsLong();
            String language = body.get("language").getAsString()
                    .trim().toUpperCase(Locale.ROOT);
            String section = body.get("section").getAsString()
                    .trim().toUpperCase(Locale.ROOT);
            String sourceCode = body.get("sourceCode").getAsString();

            if (questionId <= 0) {
                sendError(response, 400, "Invalid question ID.");
                return;
            }

            if (!LANGUAGES.contains(language)) {
                sendError(response, 400, "Unsupported programming language.");
                return;
            }

            if (!section.equals("CODING_TEST")
                    && !section.equals("DAILY_ASSIGNMENT")) {
                sendError(response, 400, "Invalid submission section.");
                return;
            }

            if (sourceCode.isBlank()) {
                sendError(response, 400, "Please enter your code.");
                return;
            }

            if (sourceCode.length() > 100_000) {
                sendError(response, 400, "Your code is too large.");
                return;
            }

            String sql = """
                INSERT INTO student_code_submissions
                    (student_id, question_id, section, language, source_code)
                SELECT ?, id, ?, ?, ?
                FROM coding_questions
                WHERE id = ? AND is_active = 1
                """;

            try (Connection con = DBConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement(
                         sql, PreparedStatement.RETURN_GENERATED_KEYS)) {

                ps.setLong(1, studentId);
                ps.setString(2, section);
                ps.setString(3, language);
                ps.setString(4, sourceCode);
                ps.setLong(5, questionId);

                if (ps.executeUpdate() != 1) {
                    sendError(response, 404,
                            "The selected coding question is unavailable.");
                    return;
                }

                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) {
                        JsonObject result = new JsonObject();
                        result.addProperty("success", true);
                        result.addProperty("submissionId", keys.getLong(1));
                        result.addProperty("questionId", questionId);
                        result.addProperty("section", section);
                        result.addProperty("language", language);
                        result.addProperty("message",
                                "Code submitted and saved successfully.");
                        sendJson(response, 200, result);
                    } else {
                        sendError(response, 500,
                                "Submission saved, but its ID was not returned.");
                    }
                }
            }

        } catch (Exception e) {
            getServletContext().log("Unable to save code submission", e);
            sendError(response, 500, "Unable to save your code submission.");
        }
    }

    private Long getStudentId(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) return null;

        Object value = session.getAttribute("studentId");
        if (!(value instanceof Number)) return null;

        return ((Number) value).longValue();
    }

    private void prepare(HttpServletResponse response) {
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
    }

    private void sendError(HttpServletResponse response,
                           int status, String message) throws IOException {
        JsonObject result = new JsonObject();
        result.addProperty("success", false);
        result.addProperty("message", message);
        sendJson(response, status, result);
    }

    private void sendJson(HttpServletResponse response,
                          int status, JsonObject result) throws IOException {
        response.setStatus(status);
        response.getWriter().write(result.toString());
    }
}
