package de.tuda.stg.reclipse.graphview.model.persistence;

import de.tuda.stg.reclipse.graphview.Activator;
import de.tuda.stg.reclipse.graphview.javaextensions.ThrowingRunnable;
import de.tuda.stg.reclipse.graphview.model.persistence.DependencyGraph.Vertex;
import de.tuda.stg.reclipse.logger.DependencyGraphHistoryType;
import de.tuda.stg.reclipse.logger.ReactiveVariable;
import de.tuda.stg.reclipse.logger.ReactiveVariableType;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Helper class which does all the work related to the database, e.g. storing
 * variables, retrieving them etc.
 */
@SuppressWarnings("nls")
public class DatabaseHelper {

  private static final String JDBC_CLASS_NAME = "org.sqlite.JDBC";
  private static final String JDBC_USER = "";
  private static final String JDBC_PASSWORD = "";

  private static List<String> databaseSetupQueries = Arrays
          .asList("CREATE TABLE variable (idVariable  INTEGER NOT NULL PRIMARY KEY, variableId varchar(36) NOT NULL, variableName varchar(200), reactiveType integer(10), typeSimple varchar(200), typeFull varchar(200), timeFrom integer(10) NOT NULL)",
                  "CREATE TABLE variable_status (idVariableStatus  INTEGER NOT NULL PRIMARY KEY, idVariable integer(10) NOT NULL, valueString varchar(200), timeFrom integer(10) NOT NULL, timeTo integer(10) NOT NULL, exception integer(1) NOT NULL)",
                  "CREATE TABLE event (pointInTime  INTEGER NOT NULL PRIMARY KEY, type integer(10) NOT NULL, idVariable integer(10) NOT NULL, dependentVariable integer(10))",
                  "CREATE TABLE variable_dependency (idVariableStatus integer(10) NOT NULL, dependentVariable integer(10) NOT NULL, PRIMARY KEY (idVariableStatus, dependentVariable))",
                  "CREATE TABLE evaluation_duration (pointInTime INTEGER NOT NULL PRIMARY KEY, idVariable INTEGER NOT NULL, evaluationDuration BIGINT)");

  private final List<IDependencyGraphListener> listeners = new CopyOnWriteArrayList<>();
  private final String sessionId;
  private final Map<UUID, Integer> variableIdToRowIdMap = new HashMap<>();
  private final Map<Integer, Integer> variableRowIdToVariableStatusIdMap = new HashMap<>();
  private final TimeProfiler timeProfiler = new TimeProfiler();


  private Connection connection;

  private int lastPointInTime = 0;

  public DatabaseHelper(final String sessionId) {
    this.sessionId = sessionId;

    establishConnection();

    try (final Statement stmt = connection.createStatement()) {
      for (final String sql : databaseSetupQueries) {
        stmt.executeUpdate(sql);
      }
    }
    catch (final SQLException e) {
      Activator.log(e);
    }
  }

  /**
   * Reads the database connection settings from the Esper configuration file
   * and returns a fresh database connection, which automatically commits.
   *
   * @return a fresh database connection
   */
  private void establishConnection() {
    try {
      Class.forName(JDBC_CLASS_NAME);
      final String jdbcUrl = getJdbcUrl();
      connection = DriverManager.getConnection(jdbcUrl, JDBC_USER, JDBC_PASSWORD);
      connection.setAutoCommit(true);
    }
    catch (final ClassNotFoundException | SQLException e) {
      Activator.log(e);
    }
  }

  private void beginTx() throws PersistenceException {
    try {
      connection.setAutoCommit(false);
    }
    catch (final SQLException e) {
      throw new PersistenceException(e);
    }
  }

  private void closeTx() throws PersistenceException {
    try {
      connection.setAutoCommit(true);
    }
    catch (final SQLException e) {
      throw new PersistenceException(e);
    }
  }

  private void commit() throws PersistenceException {
    try {
      connection.commit();
    }
    catch (final SQLException e) {
      throw new PersistenceException(e);
    }
  }

  private void rollback() throws PersistenceException {
    try {
      connection.rollback();
    }
    catch (final SQLException e) {
      throw new PersistenceException(e);
    }
  }

  private void executeOrRollback(final ThrowingRunnable<PersistenceException> runnable) throws PersistenceException {
    try {
      beginTx();
      runnable.run();
      commit();
    }
    catch (PersistenceException | RuntimeException exception) {
      rollback();
      throw exception;
    }
    finally {
      closeTx();
    }
  }

  public Connection getConnection() {
    return connection;
  }

  public void addDependencyGraphListener(final IDependencyGraphListener listener) {
    if (!listeners.contains(listener)) {
      listeners.add(listener);
    }
  }

  public void removeDependencyGraphListener(final IDependencyGraphListener listener) {
    listeners.remove(listener);
  }

  protected void fireChangedEvent(final DependencyGraphHistoryType type, final int pointInTime) {
    for (final IDependencyGraphListener l : listeners) {
      l.onDependencyGraphChanged(type, pointInTime);
    }
  }

  /**
   * @return the last point in time of the dependency graph history
   */
  public int getLastPointInTime() {
    return lastPointInTime;
  }

  public synchronized void logNodeCreated(final ReactiveVariable r) throws PersistenceException {
    executeOrRollback(() -> {
      nextPointInTime();

      final int idVariable = createVariable(r);
      createVariableStatus(r, idVariable, null);
      createEvent(r, idVariable, null);

      r.setPointInTime(lastPointInTime);
    });

    fireChangedEvent(DependencyGraphHistoryType.NODE_CREATED, lastPointInTime);
  }

  public synchronized void logNodeAttached(final ReactiveVariable r, final UUID dependentId) throws PersistenceException {
    executeOrRollback(() -> {
      nextPointInTime();

      final int idVariable = findVariableById(r.getId());
      final int dependentVariable = findVariableById(dependentId);

      final int oldVariableStatus = findActiveVariableStatus(idVariable);

      createVariableStatus(r, idVariable, oldVariableStatus, dependentVariable, null);
      createEvent(r, idVariable, dependentVariable);

      // TODO use node name instead of id in additionalInformation
      final String additionalInformation = r.getId() + "->" + dependentId;
      r.setPointInTime(lastPointInTime);
      r.setAdditionalInformation(additionalInformation);
      r.setConnectedWith(dependentId);
      commit();
    });

    fireChangedEvent(DependencyGraphHistoryType.NODE_ATTACHED, lastPointInTime);
  }

  public synchronized void logNodeEvaluationStarted(final ReactiveVariable r) throws PersistenceException {
    executeOrRollback(() -> {
      nextPointInTime();

      final int idVariable = findVariableById(r.getId());
      final int oldVariableStatus = findActiveVariableStatus(idVariable);
      createVariableStatus(r, idVariable, oldVariableStatus, null);
      createEvent(r, idVariable, null);
      timeProfiler.logNodeEvaluationStarted(r);

      r.setPointInTime(lastPointInTime);

      commit();
    });

    fireChangedEvent(DependencyGraphHistoryType.NODE_EVALUATION_STARTED, lastPointInTime);
  }

  public synchronized void logNodeEvaluationEnded(final ReactiveVariable r) throws PersistenceException {
    executeOrRollback(() -> {
      nextPointInTime();

      final int idVariable = findVariableById(r.getId());
      final int oldVariableStatus = findActiveVariableStatus(idVariable);
      createVariableStatus(r, idVariable, oldVariableStatus, null);
      createEvent(r, idVariable, null);
      timeProfiler.logNodeEvaluationEnded(r);
      final Long evaluationDuration = timeProfiler.evaluationTimes.getOrDefault(r.getId(), 0L);
      final int rowId = variableIdToRowIdMap.get(r.getId());
      createEvaluationDurationEntry(lastPointInTime, rowId, evaluationDuration);

      r.setPointInTime(lastPointInTime);

      commit();
    });

    fireChangedEvent(DependencyGraphHistoryType.NODE_EVALUATION_ENDED, lastPointInTime);
  }

  public synchronized void logNodeEvaluationEndedWithException(final ReactiveVariable r, final Exception exception) throws PersistenceException {
    executeOrRollback(() -> {
      nextPointInTime();

      final int idVariable = findVariableById(r.getId());
      final int oldVariableStatus = findActiveVariableStatus(idVariable);
      createVariableStatus(r, idVariable, oldVariableStatus, exception);
      createEvent(r, idVariable, null);
      timeProfiler.logNodeEvaluationEndedWithException(r, exception);
      final Long evaluationDuration = timeProfiler.evaluationTimes.getOrDefault(r.getId(), 0L);
      final int rowId = variableIdToRowIdMap.get(r.getId());
      createEvaluationDurationEntry(lastPointInTime, rowId, evaluationDuration);

      r.setPointInTime(lastPointInTime);

      commit();
    });

    fireChangedEvent(DependencyGraphHistoryType.NODE_EVALUATION_ENDED_WITH_EXCEPTION, lastPointInTime);
  }

  public synchronized void logNodeValueSet(final ReactiveVariable r) throws PersistenceException {
    executeOrRollback(() -> {
      nextPointInTime();

      final int idVariable = findVariableById(r.getId());
      final int oldVariableStatus = findActiveVariableStatus(idVariable);
      createVariableStatus(r, idVariable, oldVariableStatus, null);
      createEvent(r, idVariable, null);

      r.setPointInTime(lastPointInTime);

      commit();
    });

    fireChangedEvent(DependencyGraphHistoryType.NODE_VALUE_SET, lastPointInTime);
  }

  private void nextPointInTime() {
    lastPointInTime++;
  }

  private int getAutoIncrementKey(final Statement stmt) throws PersistenceException {
    try (final ResultSet rs = stmt.getGeneratedKeys()) {
      rs.next();
      return rs.getInt(1);
    }
    catch (final SQLException e) {
      throw new PersistenceException(e);
    }
  }

  public int findVariableById(final UUID id) throws PersistenceException {
    if (!variableIdToRowIdMap.containsKey(id)) {
      throw new PersistenceException("unknown variable with id " + id);
    }

    return variableIdToRowIdMap.get(id);
  }

  private int findActiveVariableStatus(final int idVariable) throws PersistenceException {
    if (!variableRowIdToVariableStatusIdMap.containsKey(idVariable)) {
      throw new PersistenceException("no active status for variable " + idVariable);
    }

    return variableRowIdToVariableStatusIdMap.get(idVariable);
  }

  private int createVariable(final ReactiveVariable variable) throws PersistenceException {
    final String insertStmt = "INSERT INTO variable (variableId, variableName, reactiveType, typeSimple, typeFull, timeFrom) VALUES (?, ?, ?, ?, ?, ?)";

    try (final PreparedStatement stmt = connection.prepareStatement(insertStmt)) {
      stmt.setString(1, variable.getId().toString());
      stmt.setString(2, variable.getName());
      stmt.setInt(3, variable.getReactiveVariableType().ordinal());
      stmt.setString(4, variable.getTypeSimple());
      stmt.setString(5, variable.getTypeFull());
      stmt.setInt(6, lastPointInTime);
      stmt.executeUpdate();

      final int key = getAutoIncrementKey(stmt);
      variableIdToRowIdMap.put(variable.getId(), key);
      return key;
    }
    catch (final SQLException e) {
      throw new PersistenceException(e);
    }
  }

  private int createVariableStatus(final ReactiveVariable variable, final int idVariable, final Exception exception) throws PersistenceException {
    final String insertStmt = "INSERT INTO variable_status (idVariable, valueString, timeFrom, timeTo, exception) VALUES (?, ?, ?, ?, ?)";

    try (PreparedStatement stmt = connection.prepareStatement(insertStmt)) {

      stmt.setInt(1, idVariable);
      stmt.setInt(3, lastPointInTime);
      stmt.setInt(4, Integer.MAX_VALUE);

      if (exception != null) {
        stmt.setString(2, exception.toString());
        stmt.setBoolean(5, true);
      }
      else {
        stmt.setString(2, variable.getValueString());
        stmt.setBoolean(5, false);
      }

      stmt.executeUpdate();

      final int key = getAutoIncrementKey(stmt);
      variableRowIdToVariableStatusIdMap.put(idVariable, key);
      return key;
    }
    catch (final SQLException e) {
      throw new PersistenceException(e);
    }
  }

  private int createVariableStatus(final ReactiveVariable variable, final int idVariable, final int oldVariableStatus, final Exception exception) throws PersistenceException {
    final String updateStmt = "UPDATE variable_status SET timeTo = ? WHERE idVariableStatus = ?";

    try (PreparedStatement stmt = connection.prepareStatement(updateStmt)) {
      stmt.setInt(1, lastPointInTime - 1);
      stmt.setInt(2, oldVariableStatus);
      stmt.executeUpdate();
    }
    catch (final SQLException e) {
      throw new PersistenceException(e);
    }

    final int id = createVariableStatus(variable, idVariable, exception);

    final String copyStmt = "INSERT INTO variable_dependency (idVariableStatus, dependentVariable) SELECT ?, dependentVariable FROM variable_dependency WHERE idVariableStatus = ?";

    try (PreparedStatement stmt = connection.prepareStatement(copyStmt)) {
      stmt.setInt(1, id);
      stmt.setInt(2, oldVariableStatus);
      stmt.executeUpdate();
    }
    catch (final SQLException e) {
      throw new PersistenceException(e);
    }

    return id;
  }

  private int createVariableStatus(final ReactiveVariable variable, final int idVariable, final int oldVariableStatus, final int dependentVariable, final Exception exception)
          throws PersistenceException {
    final int id = createVariableStatus(variable, idVariable, oldVariableStatus, exception);

    final String insertStmt = "REPLACE INTO variable_dependency (idVariableStatus, dependentVariable) VALUES (?, ?)";

    try (PreparedStatement stmt = connection.prepareStatement(insertStmt)) {
      stmt.setInt(1, id);
      stmt.setInt(2, dependentVariable);
      stmt.executeUpdate();
    }
    catch (final SQLException e) {
      throw new PersistenceException(e);
    }

    return id;
  }

  private void createEvent(final ReactiveVariable variable, final int idVariable, final Integer dependentVariable) throws PersistenceException {
    final String insertStmt = "INSERT INTO event (pointInTime, type, idVariable, dependentVariable) VALUES (?, ?, ?, ?)";

    try (PreparedStatement stmt = connection.prepareStatement(insertStmt)) {
      stmt.setInt(1, lastPointInTime);
      stmt.setInt(2, variable.getDependencyGraphHistoryType().ordinal());
      stmt.setInt(3, idVariable);

      if (dependentVariable != null) {
        stmt.setInt(4, dependentVariable);
      }
      else {
        stmt.setNull(4, Types.INTEGER);
      }

      stmt.executeUpdate();
    }
    catch (final SQLException e) {
      throw new PersistenceException(e);
    }
  }

  private void createEvaluationDurationEntry(final int pointInTime, final int idVariable, final long evaluationDuration) throws PersistenceException {
    final String statementString = "INSERT INTO evaluation_duration (pointInTime, idVariable, evaluationDuration) VALUES (?, ?, ?)";

    try (final PreparedStatement statement = connection.prepareStatement(statementString)) {
      statement.setInt(1, pointInTime);
      statement.setInt(2, idVariable);
      statement.setLong(3, evaluationDuration);

      statement.executeUpdate();
    }
    catch (final SQLException exception) {
      throw new PersistenceException(exception);
    }
  }

  public List<ReactiveVariable> getReVarsWithDependencies(final int pointInTime) throws PersistenceException {
    final List<ReactiveVariable> variables = new ArrayList<>();

    final String query = "SELECT variable.variableId AS variableId, variable.variableName AS variableName, variable.reactiveType AS reactiveType, event.type AS historyType, variable.typeSimple AS typeSimple, variable.typeFull AS typeFull, variable_status.valueString AS valueString, variable_status.idVariableStatus AS idVariableStatus, variable_status.exception AS exception FROM variable, event JOIN variable_status ON variable_status.idVariable = variable.idVariable WHERE event.pointInTime = ? AND variable.timeFrom <= ? AND variable_status.timeFrom <= ? AND variable_status.timeTo >= ?";
    try (final PreparedStatement stmt = connection.prepareStatement(query)) {
      stmt.setInt(1, pointInTime);
      stmt.setInt(2, pointInTime);
      stmt.setInt(3, pointInTime);
      stmt.setInt(4, pointInTime);

      try (final ResultSet rs = stmt.executeQuery()) {
        while (rs.next()) {
          final ReactiveVariable r = reactiveVariableFromResultSet(rs, pointInTime);
          updateConnectedWith(r, rs);
          variables.add(r);
        }
      }
    }
    catch (final SQLException e) {
      throw new PersistenceException(e);
    }

    return variables;
  }

  private ReactiveVariable reactiveVariableFromResultSet(final ResultSet rs, final int pointInTime) throws SQLException {
    final ReactiveVariable r = new ReactiveVariable();
    r.setId(UUID.fromString(rs.getString("variableId")));
    r.setName(rs.getString("variableName"));
    r.setReactiveVariableType(ReactiveVariableType.values()[rs.getInt("reactiveType")]);
    r.setPointInTime(pointInTime);
    //r.setDependencyGraphHistoryType(DependencyGraphHistoryType.values()[rs.getInt("historyType")]);
    r.setAdditionalInformation(""); // TODO load additional information field
    r.setTypeSimple(rs.getString("typeSimple"));
    r.setTypeFull(rs.getString("typeFull"));
    r.setAdditionalKeys(new HashMap<String, Object>()); // TODO load additional
    // keys field
    r.setValueString(rs.getString("valueString"));
    r.setExceptionOccured(rs.getBoolean("exception"));

    return r;
  }

  private void updateConnectedWith(final ReactiveVariable r, final ResultSet rs) throws SQLException {
    final int idVariableStatus = rs.getInt("idVariableStatus");

    final String query = "SELECT variableId FROM variable JOIN variable_dependency ON variable_dependency.dependentVariable = variable.idVariable WHERE variable_dependency.idVariableStatus = ?";
    try (final PreparedStatement stmt = connection.prepareStatement(query)) {
      stmt.setInt(1, idVariableStatus);

      try (ResultSet rs2 = stmt.executeQuery()) {
        while (rs2.next()) {
          final String id = rs2.getString(1);
          r.setConnectedWith(UUID.fromString(id));
        }
      }
    }
  }

  public UUID getIdFromName(final String name) {
    // TODO variables should be referenced by their IDs

    final String sql = "SELECT variableId FROM variable WHERE variableName = ?";
    try (final PreparedStatement stmt = connection.prepareStatement(sql)) {
      stmt.setString(1, name);
      try (final ResultSet rs = stmt.executeQuery()) {
        while (rs.next()) {
          return UUID.fromString(rs.getString("variableId"));
        }
      }
    }
    catch (final SQLException e) {
      Activator.log(e);
    }
    return null;
  }

  public DependencyGraph getDependencyGraph(final int pointInTime) throws PersistenceException {
    final List<Vertex> vertices = loadVertices(pointInTime);
    connectVertices(vertices, pointInTime);
    loadEvaluationTimes(vertices, pointInTime);
    loadSumOfEvaluationTimes(vertices, pointInTime);
    return new DependencyGraph(vertices);
  }

  private List<Vertex> loadVertices(final int pointInTime) throws PersistenceException {
    final List<Vertex> vertices = new ArrayList<>();

    final String query = "SELECT variable.idVariable AS idVariable, variable.variableId AS variableId, variable.variableName AS variableName, variable.reactiveType AS reactiveType, variable.typeSimple AS typeSimple, variable.typeFull AS typeFull, variable_status.valueString AS valueString, variable.timeFrom AS timeFrom, variable_status.exception AS exception FROM variable JOIN variable_status ON variable_status.idVariable = variable.idVariable WHERE variable.timeFrom <= ? AND variable_status.timeFrom <= ? AND variable_status.timeTo >= ?";
    try (final PreparedStatement stmt = connection.prepareStatement(query)) {
      stmt.setInt(1, pointInTime);
      stmt.setInt(2, pointInTime);
      stmt.setInt(3, pointInTime);

      try (final ResultSet rs = stmt.executeQuery()) {
        while (rs.next()) {
          final int idVariable = rs.getInt("idVariable");
          final int created = rs.getInt("timeFrom");
          final ReactiveVariable r = reactiveVariableFromResultSet(rs, pointInTime);
          final Vertex vertex = new Vertex(idVariable, created, r);
          vertices.add(vertex);
        }
      }
    }
    catch (final SQLException e) {
      throw new PersistenceException(e);
    }

    return vertices;
  }

  private void connectVertices(final List<Vertex> vertices, final int pointInTime) throws PersistenceException {
    final Map<Integer, Vertex> vertexMap = new HashMap<>();

    for (final Vertex vertex : vertices) {
      vertexMap.put(vertex.getId(), vertex);
    }

    final String dependencyQuery = "SELECT variable_status.idVariable AS idVariable, variable_dependency.dependentVariable AS dependentVariable FROM variable_dependency JOIN variable_status ON variable_status.idVariableStatus = variable_dependency.idVariableStatus WHERE variable_status.timeFrom <= ? AND variable_status.timeTo >= ?";
    try (final PreparedStatement stmt = connection.prepareStatement(dependencyQuery)) {
      stmt.setInt(1, pointInTime);
      stmt.setInt(2, pointInTime);

      try (final ResultSet rs = stmt.executeQuery()) {
        while (rs.next()) {
          final int idVariable = rs.getInt("idVariable");
          final int dependentId = rs.getInt("dependentVariable");

          if (!vertexMap.containsKey(idVariable)) {
            throw new PersistenceException("vertex for variable with internal id " + idVariable + " is missing");
          }

          final Vertex v = vertexMap.get(idVariable);
          final Vertex dependent = vertexMap.get(dependentId);
          v.addConnectedVertex(dependent);
        }
      }
    }
    catch (final SQLException e) {
      throw new PersistenceException(e);
    }
  }

  private void loadEvaluationTimes(final List<Vertex> vertices, final int pointInTime) throws PersistenceException {
    final Map<Integer, Vertex> vertexMap = new HashMap<>();

    for (final Vertex vertex : vertices) {
      vertexMap.put(vertex.getId(), vertex);
    }

    final String evaluationTimesQuery = "SELECT idVariable, evaluationDuration FROM evaluation_duration WHERE pointInTime <= ? GROUP BY idVariable HAVING pointInTime == MAX(pointInTime)";

    try (final PreparedStatement statement = connection.prepareStatement(evaluationTimesQuery)) {
      statement.setInt(1, pointInTime);

      try (ResultSet rs = statement.executeQuery()) {
        while (rs.next()) {
          final int idVariable = rs.getInt("idVariable");
          final Long evaluationDuration = rs.getLong("evaluationDuration");

          if (!vertexMap.containsKey(idVariable)) {
            throw new PersistenceException("vertex for variable with internal id " + idVariable + " is missing");
          }

          final Vertex vertex = vertexMap.get(idVariable);
          vertex.getVariable().getAdditionalKeys().put("evaluationDuration", evaluationDuration);
        }
      }
    } catch (final SQLException e) {
      throw new PersistenceException(e);
    }
  }

  private void loadSumOfEvaluationTimes(final List<Vertex> vertices, final int pointInTime) throws PersistenceException {
    final Map<Integer, Vertex> vertexMap = new HashMap<>();

    for (final Vertex vertex : vertices) {
      vertexMap.put(vertex.getId(), vertex);
    }

    final String evaluationTimesQuery = "SELECT idVariable, SUM(evaluationDuration) AS sumOfEvaluationDurations FROM evaluation_duration WHERE pointInTime <= ? GROUP BY idVariable";

    try (final PreparedStatement statement = connection.prepareStatement(evaluationTimesQuery)) {
      statement.setInt(1, pointInTime);

      try (ResultSet rs = statement.executeQuery()) {
        while (rs.next()) {
          final int idVariable = rs.getInt("idVariable");
          final Long sumOfEvaluationDurations = rs.getLong("sumOfEvaluationDurations");

          if (!vertexMap.containsKey(idVariable)) {
            throw new PersistenceException("vertex for variable with internal id " + idVariable + " is missing");
          }

          final Vertex vertex = vertexMap.get(idVariable);
          vertex.getVariable().getAdditionalKeys().put("sumOfEvaluationDurations", sumOfEvaluationDurations);
        }
      }
    } catch (final SQLException e) {
      throw new PersistenceException(e);
    }
  }

  public void close() {
    if (connection != null) {
      try {
        connection.close();
      }
      catch (final SQLException e) {
        Activator.log(e);
      }
    }
  }

  protected String getJdbcUrl() {
    // shared in-memory database
    return "jdbc:sqlite:file:" + sessionId + "?mode=memory&cache=shared";
  }

  protected String getJdbcClassName() {
    return JDBC_CLASS_NAME;
  }

  protected String getJdbcUser() {
    return JDBC_USER;
  }

  protected String getJdbcPassword() {
    return JDBC_PASSWORD;
  }

  public String getSessionId() {
    return sessionId;
  }
}