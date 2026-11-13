
package com.examtrack.controller;

import com.examtrack.util.DBConnection;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.BufferedReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@WebServlet("/weekly-test/*")
public class WeeklyTestServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;
    private static final int QUESTION_COUNT = 20;
    private static final String SESSION_ATTEMPT_ID =
            "weeklyTestAttemptId";

    private final Gson gson = new Gson();

    /*
     * GET /weekly-test/questions
     * Creates a weekly-test attempt and returns 20 aptitude questions.
     */
    @Override
    protected void doGet(
            HttpServletRequest request,
            HttpServletResponse response)
            throws ServletException, IOException {

        prepareResponse(response);

        Long studentId = getStudentId(request);

        if (studentId == null) {
            sendError(response, 401, "Please log in before starting the test.");
            return;
        }

        List<Map<String, Object>> questions = new ArrayList<>();

        String questionsSql = """
                SELECT id, question_text, option_a, option_b,
                       option_c, option_d, category, difficulty, marks
                FROM aptitude_questions
                WHERE is_active = 1
                ORDER BY RAND()
                LIMIT 20
                """;

        try (Connection connection = DBConnection.getConnection()) {

            try (PreparedStatement ps =
                         connection.prepareStatement(questionsSql);
                 ResultSet rs = ps.executeQuery()) {

                while (rs.next()) {
                    Map<String, Object> question = new HashMap<>();

                    question.put("id", rs.getLong("id"));
                    question.put("questionText",
                            rs.getString("question_text"));
                    question.put("optionA", rs.getString("option_a"));
                    question.put("optionB", rs.getString("option_b"));
                    question.put("optionC", rs.getString("option_c"));
                    question.put("optionD", rs.getString("option_d"));
                    question.put("category", rs.getString("category"));
                    question.put("difficulty", rs.getString("difficulty"));
                    question.put("marks", rs.getBigDecimal("marks"));

                    // Never expose correct_option to the frontend.
                    questions.add(question);
                }
            }

            if (questions.size() != QUESTION_COUNT) {
                sendError(response, 400,
                        "At least 20 active aptitude questions are required.");
                return;
            }

            connection.setAutoCommit(false);

            try {
                long attemptId;

                String attemptSql = """
                        INSERT INTO exam_attempts
                            (student_id, exam_type, status,
                             aptitude_score, coding_score, total_score)
                        VALUES (?, 'WEEKLY_TEST', 'IN_PROGRESS', 0, 0, 0)
                        """;

                try (PreparedStatement ps = connection.prepareStatement(
                        attemptSql, Statement.RETURN_GENERATED_KEYS)) {

                    ps.setLong(1, studentId);
                    ps.executeUpdate();

                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        if (!keys.next()) {
                            throw new SQLException(
                                    "Could not create weekly-test attempt.");
                        }
                        attemptId = keys.getLong(1);
                    }
                }

                String questionSql = """
                        INSERT INTO exam_attempt_questions
                            (attempt_id, aptitude_question_id,
                             question_number, question_type, marks_awarded)
                        VALUES (?, ?, ?, 'APTITUDE', 0)
                        """;

                try (PreparedStatement ps =
                             connection.prepareStatement(questionSql)) {

                    int number = 1;

                    for (Map<String, Object> question : questions) {
                        ps.setLong(1, attemptId);
                        ps.setLong(2,
                                ((Number) question.get("id")).longValue());
                        ps.setInt(3, number++);
                        ps.addBatch();
                    }

                    ps.executeBatch();
                }

                connection.commit();

                HttpSession session = request.getSession();
                session.setAttribute(SESSION_ATTEMPT_ID, attemptId);

                Map<String, Object> result = new HashMap<>();
                result.put("success", true);
                result.put("attemptId", attemptId);
                result.put("totalQuestions", questions.size());
                result.put("questions", questions);

                response.getWriter().write(gson.toJson(result));

            } catch (Exception e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }

        } catch (Exception e) {
            getServletContext().log(
                    "Failed to load Weekly Test questions.", e);
            sendError(response, 500,
                    "Could not load Weekly Test. Check the server logs.");
        }
    }

    /*
     * POST /weekly-test/submit
     *
     * JSON:
     * {
     *   "answers": {
     *     "12": "A",
     *     "15": "C"
     *   }
     * }
     *
     * Keys are aptitude question IDs.
     */
    @Override
    protected void doPost(
            HttpServletRequest request,
            HttpServletResponse response)
            throws ServletException, IOException {

        prepareResponse(response);

        Long studentId = getStudentId(request);

        if (studentId == null) {
            sendError(response, 401,
                    "Please log in before submitting the test.");
            return;
        }

        HttpSession session = request.getSession(false);

        if (session == null ||
                !(session.getAttribute(SESSION_ATTEMPT_ID)
                        instanceof Number)) {
            sendError(response, 400,
                    "No active Weekly Test found. Start a new test.");
            return;
        }

        long attemptId = ((Number) session.getAttribute(
                SESSION_ATTEMPT_ID)).longValue();

        JsonObject body;

        try (BufferedReader reader = request.getReader()) {
            body = JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception e) {
            sendError(response, 400, "Invalid JSON request.");
            return;
        }

        if (body == null || !body.has("answers")
                || !body.get("answers").isJsonObject()) {
            sendError(response, 400, "The answers object is missing.");
            return;
        }

        JsonObject answers = body.getAsJsonObject("answers");

        String verifyAttemptSql = """
                SELECT id
                FROM exam_attempts
                WHERE id = ?
                  AND student_id = ?
                  AND exam_type = 'WEEKLY_TEST'
                  AND status = 'IN_PROGRESS'
                FOR UPDATE
                """;

        String questionsSql = """
                SELECT eaq.id AS attempt_question_id,
                       aq.id AS question_id,
                       aq.correct_option,
                       aq.marks
                FROM exam_attempt_questions eaq
                JOIN aptitude_questions aq
                  ON aq.id = eaq.aptitude_question_id
                WHERE eaq.attempt_id = ?
                  AND eaq.question_type = 'APTITUDE'
                """;

        String saveAnswerSql = """
                INSERT INTO aptitude_answers
                    (attempt_question_id, selected_option, is_correct)
                VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    selected_option = VALUES(selected_option),
                    is_correct = VALUES(is_correct),
                    answered_at = CURRENT_TIMESTAMP
                """;

        String updateQuestionSql = """
                UPDATE exam_attempt_questions
                SET marks_awarded = ?
                WHERE id = ? AND attempt_id = ?
                """;

        String updateAttemptSql = """
                UPDATE exam_attempts
                SET aptitude_score = ?,
                    coding_score = 0,
                    total_score = ?,
                    status = 'SUBMITTED',
                    submitted_at = CURRENT_TIMESTAMP
                WHERE id = ?
                  AND student_id = ?
                  AND exam_type = 'WEEKLY_TEST'
                  AND status = 'IN_PROGRESS'
                """;

        String saveResultSql = """
                INSERT INTO exam_results
                    (student_id, exam_type, aptitude_score,
                     coding_score, total_score)
                VALUES (?, 'WEEKLY_TEST', ?, 0, ?)
                """;

        try (Connection connection = DBConnection.getConnection()) {

            connection.setAutoCommit(false);

            try {
                // Lock and verify the attempt before scoring.
                try (PreparedStatement ps =
                             connection.prepareStatement(verifyAttemptSql)) {

                    ps.setLong(1, attemptId);
                    ps.setLong(2, studentId);

                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            connection.rollback();
                            sendError(response, 400,
                                    "This Weekly Test is already submitted "
                                            + "or is invalid.");
                            return;
                        }
                    }
                }

                BigDecimal totalScore = BigDecimal.ZERO;
                int totalQuestions = 0;
                int correctAnswers = 0;

                try (PreparedStatement questionPs =
                             connection.prepareStatement(questionsSql);
                     PreparedStatement answerPs =
                             connection.prepareStatement(saveAnswerSql);
                     PreparedStatement updatePs =
                             connection.prepareStatement(updateQuestionSql)) {

                    questionPs.setLong(1, attemptId);

                    try (ResultSet rs = questionPs.executeQuery()) {
                        while (rs.next()) {
                            totalQuestions++;

                            long attemptQuestionId =
                                    rs.getLong("attempt_question_id");
                            long questionId = rs.getLong("question_id");

                            String correctOption =
                                    rs.getString("correct_option");
                            BigDecimal marks = rs.getBigDecimal("marks");

                            if (marks == null) {
                                marks = BigDecimal.ZERO;
                            }

                            String selectedOption = null;
                            String key = String.valueOf(questionId);

                            if (answers.has(key)
                                    && !answers.get(key).isJsonNull()
                                    && answers.get(key).isJsonPrimitive()
                                    && answers.get(key).getAsJsonPrimitive()
                                               .isString()) {
                                selectedOption = answers.get(key)
                                        .getAsString()
                                        .trim()
                                        .toUpperCase(Locale.ROOT);
                            }

                            if (selectedOption != null &&
                                    !selectedOption.matches("[ABCD]")) {
                                selectedOption = null;
                            }

                            boolean isCorrect = selectedOption != null
                                    && selectedOption.equalsIgnoreCase(
                                            correctOption);

                            BigDecimal awarded = isCorrect
                                    ? marks : BigDecimal.ZERO;

                            if (isCorrect) {
                                correctAnswers++;
                                totalScore = totalScore.add(awarded);
                            }

                            answerPs.setLong(1, attemptQuestionId);

                            if (selectedOption == null) {
                                answerPs.setNull(2,
                                        java.sql.Types.VARCHAR);
                            } else {
                                answerPs.setString(2, selectedOption);
                            }

                            answerPs.setBoolean(3, isCorrect);
                            answerPs.addBatch();

                            updatePs.setBigDecimal(1, awarded);
                            updatePs.setLong(2, attemptQuestionId);
                            updatePs.setLong(3, attemptId);
                            updatePs.addBatch();
                        }
                    }

                    answerPs.executeBatch();
                    updatePs.executeBatch();
                }

                if (totalQuestions != QUESTION_COUNT) {
                    throw new SQLException(
                            "Expected 20 questions but found "
                                    + totalQuestions + ".");
                }

                try (PreparedStatement ps =
                             connection.prepareStatement(updateAttemptSql)) {

                    ps.setBigDecimal(1, totalScore);
                    ps.setBigDecimal(2, totalScore);
                    ps.setLong(3, attemptId);
                    ps.setLong(4, studentId);

                    if (ps.executeUpdate() != 1) {
                        throw new SQLException(
                                "Could not update the Weekly Test attempt.");
                    }
                }

                try (PreparedStatement ps =
                             connection.prepareStatement(saveResultSql)) {

                    ps.setLong(1, studentId);
                    ps.setBigDecimal(2, totalScore);
                    ps.setBigDecimal(3, totalScore);
                    ps.executeUpdate();
                }

                connection.commit();

                session.removeAttribute(SESSION_ATTEMPT_ID);

                Map<String, Object> result = new HashMap<>();
                result.put("success", true);
                result.put("attemptId", attemptId);
                result.put("totalQuestions", totalQuestions);
                result.put("correctAnswers", correctAnswers);
                result.put("incorrectOrUnanswered",
                        totalQuestions - correctAnswers);
                result.put("score", totalScore);
                result.put("outOf", totalQuestions);
                result.put("codingScore", 0);
                result.put("message",
                        "Weekly Test submitted successfully.");

                response.getWriter().write(gson.toJson(result));

            } catch (Exception e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }

        } catch (Exception e) {
            getServletContext().log(
                    "Failed to submit Weekly Test.", e);
            sendError(response, 500,
                    "Could not submit Weekly Test. Check the server logs.");
        }
    }

    private Long getStudentId(HttpServletRequest request) {
        HttpSession session = request.getSession(false);

        if (session != null) {
            Object value = session.getAttribute("studentId");

            if (value instanceof Number) {
                return ((Number) value).longValue();
            }
        }

        // Local development only; disable before public deployment.
        if ("true".equalsIgnoreCase(
                System.getenv("EXAMTRACK_DEV_MODE"))) {
            return 1L;
        }

        return null;
    }

    private void prepareResponse(HttpServletResponse response) {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
    }

    private void sendError(
            HttpServletResponse response,
            int status,
            String message) throws IOException {

        response.setStatus(status);

        Map<String, Object> error = new HashMap<>();
        error.put("success", false);
        error.put("message", message);

        response.getWriter().write(gson.toJson(error));
    }
}
