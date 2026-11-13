
package com.examtrack.controller;

import com.examtrack.util.DBConnection;
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
import java.util.concurrent.ThreadLocalRandom;

@WebServlet("/quick-test/*")
public class QuickTestServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    private static final int QUESTION_COUNT = 20;
    private static final String SESSION_ATTEMPT_ID = "quickTestAttemptId";

    private final com.google.gson.Gson gson = new com.google.gson.Gson();

    /*
     * GET /quick-test/questions
     *
     * Loads 20 random active aptitude questions and creates
     * a new exam attempt in the database.
     */
    @Override
    protected void doGet(
            HttpServletRequest request,
            HttpServletResponse response)
            throws ServletException, IOException {

        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        Long studentId = getStudentId(request);

        if (studentId == null) {
            sendError(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "Please log in before starting the test.");
            return;
        }

        List<Map<String, Object>> questions = new ArrayList<>();

        String sql = """
                SELECT id, question_text, option_a, option_b,
                       option_c, option_d, category, difficulty, marks
                FROM aptitude_questions
                WHERE is_active = 1
                ORDER BY RAND()
                LIMIT 20
                """;

        try (Connection connection = DBConnection.getConnection()) {

            try (PreparedStatement ps = connection.prepareStatement(sql);
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

                    // Never send correct_option to the browser.
                    questions.add(question);
                }
            }

            if (questions.size() < QUESTION_COUNT) {
                sendError(response, HttpServletResponse.SC_BAD_REQUEST,
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
                        VALUES (?, 'QUICK_TEST', 'IN_PROGRESS', 0, 0, 0)
                        """;

                try (PreparedStatement ps = connection.prepareStatement(
                        attemptSql, Statement.RETURN_GENERATED_KEYS)) {

                    ps.setLong(1, studentId);
                    ps.executeUpdate();

                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        if (!keys.next()) {
                            throw new SQLException(
                                    "Could not create exam attempt.");
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

                    int questionNumber = 1;

                    for (Map<String, Object> question : questions) {
                        ps.setLong(1, attemptId);
                        ps.setLong(2, ((Number) question.get("id")).longValue());
                        ps.setInt(3, questionNumber++);
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
            getServletContext().log("Failed to load Quick Test questions", e);

            sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Could not load the test. Check the application logs.");
        }
    }

    /*
     * POST /quick-test/submit
     *
     * Expected JSON:
     * {
     *   "answers": {
     *     "12": "A",
     *     "15": "C"
     *   }
     * }
     *
     * The keys are aptitude question IDs.
     */
    @Override
    protected void doPost(
            HttpServletRequest request,
            HttpServletResponse response)
            throws ServletException, IOException {

        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        Long studentId = getStudentId(request);

        if (studentId == null) {
            sendError(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "Please log in before submitting the test.");
            return;
        }

        HttpSession session = request.getSession(false);

        if (session == null ||
                session.getAttribute(SESSION_ATTEMPT_ID) == null) {
            sendError(response, HttpServletResponse.SC_BAD_REQUEST,
                    "No active Quick Test was found. Start a new test.");
            return;
        }

        long attemptId =
                ((Number) session.getAttribute(SESSION_ATTEMPT_ID)).longValue();

        JsonObject body;

        try (BufferedReader reader = request.getReader()) {
            // Correct JSON parsing: parseReader accepts a Reader.
            body = JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception e) {
            sendError(response, HttpServletResponse.SC_BAD_REQUEST,
                    "Invalid JSON request.");
            return;
        }

        if (body == null || !body.has("answers")
                || !body.get("answers").isJsonObject()) {
            sendError(response, HttpServletResponse.SC_BAD_REQUEST,
                    "The answers object is missing.");
            return;
        }

        JsonObject answers = body.getAsJsonObject("answers");

        String attemptSql = """
                SELECT id
                FROM exam_attempts
                WHERE id = ? AND student_id = ?
                  AND exam_type = 'QUICK_TEST'
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

        String answerSql = """
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
                WHERE id = ? AND student_id = ?
                """;

        String resultSql = """
                INSERT INTO exam_results
                    (student_id, exam_type, aptitude_score,
                     coding_score, total_score)
                VALUES (?, 'QUICK_TEST', ?, 0, ?)
                """;

        try (Connection connection = DBConnection.getConnection()) {

            connection.setAutoCommit(false);

            try {
                // Verify ownership and ensure this attempt is not submitted.
                try (PreparedStatement ps =
                             connection.prepareStatement(attemptSql)) {

                    ps.setLong(1, attemptId);
                    ps.setLong(2, studentId);

                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            connection.rollback();
                            sendError(response, HttpServletResponse.SC_BAD_REQUEST,
                                    "This test is already submitted or is invalid.");
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
                             connection.prepareStatement(answerSql);
                     PreparedStatement updateQuestionPs =
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

                            String selectedOption = null;

                            if (answers.has(String.valueOf(questionId))
                                    && !answers.get(String.valueOf(questionId))
                                               .isJsonNull()) {
                                selectedOption = answers
                                        .get(String.valueOf(questionId))
                                        .getAsString()
                                        .trim()
                                        .toUpperCase(Locale.ROOT);
                            }

                            if (selectedOption != null &&
                                    !selectedOption.matches("[ABCD]")) {
                                selectedOption = null;
                            }

                            boolean isCorrect =
                                    selectedOption != null &&
                                    selectedOption.equalsIgnoreCase(correctOption);

                            BigDecimal awarded = isCorrect
                                    ? marks : BigDecimal.ZERO;

                            if (isCorrect) {
                                correctAnswers++;
                                totalScore = totalScore.add(awarded);
                            }

                            answerPs.setLong(1, attemptQuestionId);

                            if (selectedOption == null) {
                                answerPs.setNull(
                                        2, java.sql.Types.VARCHAR);
                            } else {
                                answerPs.setString(2, selectedOption);
                            }

                            answerPs.setBoolean(3, isCorrect);
                            answerPs.addBatch();

                            updateQuestionPs.setBigDecimal(1, awarded);
                            updateQuestionPs.setLong(2, attemptQuestionId);
                            updateQuestionPs.setLong(3, attemptId);
                            updateQuestionPs.addBatch();
                        }
                    }

                    answerPs.executeBatch();
                    updateQuestionPs.executeBatch();
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
                                "Could not update the exam attempt.");
                    }
                }

                try (PreparedStatement ps =
                             connection.prepareStatement(resultSql)) {

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
                result.put("message", "Quick Test submitted successfully.");

                response.getWriter().write(gson.toJson(result));

            } catch (Exception e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }

        } catch (Exception e) {
            getServletContext().log("Failed to submit Quick Test", e);

            sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Could not submit the test. Check the application logs.");
        }
    }

    /*
     * For local development only:
     * EXAMTRACK_DEV_MODE=true allows testing as student ID 1.
     *
     * Remove/disable this bypass before public deployment.
     */
    private Long getStudentId(HttpServletRequest request) {

        HttpSession session = request.getSession(false);

        if (session != null) {
            Object value = session.getAttribute("studentId");

            if (value instanceof Number) {
                return ((Number) value).longValue();
            }
        }

        String devMode = System.getenv("EXAMTRACK_DEV_MODE");

        if ("true".equalsIgnoreCase(devMode)) {
            return 1L;
        }

        return null;
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
