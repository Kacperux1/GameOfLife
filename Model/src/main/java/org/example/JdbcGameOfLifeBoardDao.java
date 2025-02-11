package org.example;


import java.sql.*;
import java.util.ArrayList;

public class JdbcGameOfLifeBoardDao implements Dao<GameOfLifeBoard>, AutoCloseable {
    private final String url = "jdbc:postgresql://localhost:5455/GameOfLife";
    private final String boardName;
    private final String username = "user";
    private final String password = "password";
    boolean closed = false;

    public JdbcGameOfLifeBoardDao() {
        this.boardName = "default";
    }

    public JdbcGameOfLifeBoardDao(String boardName) {
        this.boardName = boardName;
    }

    @Override
    public GameOfLifeBoard read() throws DaoException {
        try (Connection connection = DriverManager.getConnection(url, username, password)) {

            try (PreparedStatement checkBoardStatement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM board WHERE name = ?")) {
                checkBoardStatement.setString(1, boardName);
                try (ResultSet resultSet = checkBoardStatement.executeQuery()) {
                    if (resultSet.next() && resultSet.getInt(1) == 0) {
                        throw new ObjectNotFoundException("NosuchBoard", null);
                    }
                }
            } catch (SQLException e) {
                throw new DbReadWriteException("BadStatement", e);
            }

            int maxX = -1;
            int maxY = -1;
            try (PreparedStatement dimensionStatement = connection.prepareStatement(
                    "SELECT MAX(x) AS max_x, MAX(y) AS max_y FROM cell "
                            + "JOIN board ON cell.board_id = board.board_id WHERE board.name = ?")) {
                dimensionStatement.setString(1, boardName);
                try (ResultSet dimensionResultSet = dimensionStatement.executeQuery()) {
                    if (dimensionResultSet.next()) {
                        maxX = dimensionResultSet.getInt("max_x");
                        maxY = dimensionResultSet.getInt("max_y");
                    }
                }
            }

            if (maxX == -1 || maxY == -1) {
                throw new DbReadWriteException("BadBoardDims", null);
            }

            int width = maxX + 1;
            int height = maxY + 1;
            GameOfLifeCell[][] boardArray = new GameOfLifeCell[height][width];
            for (int i = 0; i < height; i++) {
                for (int j = 0; j < width; j++) {
                    boardArray[i][j] = new GameOfLifeCell(false);
                }
            }

            try (PreparedStatement cellStatement = connection.prepareStatement(
                    "SELECT * FROM cell JOIN board ON cell.board_id = board.board_id WHERE board.name = ?")) {
                cellStatement.setString(1, boardName);
                try (ResultSet cellResultSet = cellStatement.executeQuery()) {
                    while (cellResultSet.next()) {
                        boolean state = cellResultSet.getBoolean("state");
                        int x = cellResultSet.getInt("x");
                        int y = cellResultSet.getInt("y");
                        if (x >= 0 && x < width && y >= 0 && y < height) {
                            boardArray[y][x].setCell(state);
                        }
                    }
                }
            }

            return new GameOfLifeBoard(boardArray, new PlainGameOfLifeSimulator());
        } catch (SQLException e) {
            throw new DbReadWriteException("DbConnectError", e);
        }
    }

    @Override
    public void write(GameOfLifeBoard obj) throws DaoException {
        try (Connection connection = DriverManager.getConnection(url, username, password)) {
            connection.setAutoCommit(false); // Start transaction

            try {
                createTablesIfNotExists(connection);

                try (PreparedStatement deleteCellsStatement = connection.prepareStatement(
                        "DELETE FROM cell WHERE board_id IN (SELECT board_id FROM board WHERE name = ?)")) {
                    deleteCellsStatement.setString(1, boardName);
                    deleteCellsStatement.executeUpdate();
                }

                try (PreparedStatement deleteBoardStatement = connection.prepareStatement(
                        "DELETE FROM board WHERE name = ?")) {
                    deleteBoardStatement.setString(1, boardName);
                    deleteBoardStatement.executeUpdate();
                }

                // Insert new board
                int boardId;
                try (PreparedStatement boardStatement = connection.prepareStatement(
                        "INSERT INTO board (name) VALUES (?)", Statement.RETURN_GENERATED_KEYS)) {
                    boardStatement.setString(1, boardName);
                    boardStatement.executeUpdate();
                    try (ResultSet generatedKeys = boardStatement.getGeneratedKeys()) {
                        if (generatedKeys.next()) {
                            boardId = generatedKeys.getInt(1);
                        } else {
                            throw new DbReadWriteException("BadInsert", null);
                        }
                    }
                }

                // Insert new cells
                try (PreparedStatement cellStatement = connection.prepareStatement(
                        "INSERT INTO cell (board_id, state, x, y) VALUES (?, ?, ?, ?)")) {
                    for (int i = 0; i < obj.getBoard().length; i++) {
                        for (int j = 0; j < obj.getBoard()[i].length; j++) {
                            boolean state = obj.getBoard()[i][j].isAlive();
                            cellStatement.setInt(1, boardId);
                            cellStatement.setBoolean(2, state);
                            cellStatement.setInt(3, j);
                            cellStatement.setInt(4, i);
                            cellStatement.addBatch();
                        }
                    }
                    cellStatement.executeBatch();
                }

                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw new TransactionFailedException("TransactionFailed", e);
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new DbConnectionException("DbConnectError", e);
        }
    }

    @Override
    public void close() {
        closed = true;
    }

    public boolean isClosed() {
        return closed;
    }

    public ArrayList<String> getBoardsNames() throws DaoException {
        ArrayList<String> boardsNames = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(url, username, password)) {
            createTablesIfNotExists(connection);
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery("SELECT name FROM board")) {

                while (resultSet.next()) {
                    boardsNames.add(resultSet.getString("name"));
                }

                if (boardsNames.isEmpty()) {
                    throw new ObjectNotFoundException("NoBoards", null);
                }
            }
        } catch (SQLException e) {

            throw new DaoException("BoardsNamesError", e);
        }
        return boardsNames;
    }

    @Override
    public void delete(String boardName) throws DaoException {
        ArrayList<String> boardsNames = getBoardsNames();
        if (!boardsNames.contains(boardName)) {
            throw new ObjectNotFoundException("NoDB", null);
        }

        String sql = "DELETE FROM board WHERE name = ?";

        try (Connection connection = DriverManager.getConnection(url, username, password)) {
            connection.setAutoCommit(false);

            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, boardName);
                int rowsAffected = statement.executeUpdate();

                if (rowsAffected == 0) {

                    throw new ObjectNotFoundException("NoDB", null);
                }

                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw new DaoException("DbConnectError", e);
            }
        } catch (SQLException e) {
            throw new DaoException("DbConnectError", e);
        }
    }

    private void createTablesIfNotExists(Connection connection) throws DaoException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS board ("
                    + "board_id SERIAL PRIMARY KEY, "
                    + "name VARCHAR(100) NOT NULL"
                    + ");");

            statement.execute("CREATE TABLE IF NOT EXISTS cell ("
                    + "board_id INTEGER, "
                    + "state BOOLEAN, "
                    + "x INTEGER, "
                    + "y INTEGER, "
                    + "PRIMARY KEY (board_id, x, y), " + "FOREIGN KEY (board_id) REFERENCES board(board_id) "
                    + "ON DELETE CASCADE ON UPDATE CASCADE"
                    + ");");

        } catch (SQLException e) {
            throw new DatabaseException("DbConnectError", e);
        }

    }
}
