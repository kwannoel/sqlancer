package lama;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import lama.Expression.BinaryOperation;
import lama.Expression.BinaryOperation.BinaryOperator;
import lama.Expression.ColumnName;
import lama.Expression.Constant;
import lama.Expression.Function;
import lama.Expression.OrderingTerm;
import lama.Expression.OrderingTerm.Ordering;
import lama.Expression.PostfixUnaryOperation;
import lama.Expression.UnaryOperation;
import lama.Expression.UnaryOperation.UnaryOperator;
import lama.Main.StateToReproduce;
import lama.schema.SQLite3DataType;
import lama.schema.Schema;
import lama.schema.Schema.Column;
import lama.schema.Schema.RowValue;
import lama.schema.Schema.Table;
import lama.sqlite3.SQLite3Visitor;

public class QueryGenerator {

	private Connection database;
	private Schema s;

	enum Database {
		MYSQL, SQLITE
	}

	public static final Database DATABASE = Database.SQLITE;

	public QueryGenerator(Connection con) throws SQLException {
		this.database = con;
		s = Schema.fromConnection(database);
	}

	public void generateAndCheckQuery(StateToReproduce state) throws SQLException {
		Table t = s.getRandomTable();
		assert t != null;
		SelectStatement selectStatement = new SelectStatement();
		selectStatement.setSelectType(Randomly.fromOptions(SelectStatement.SelectType.values()));
		selectStatement.setFromTables(Arrays.asList(t));
		List<Column> columns = t.getColumns();
		RowValue rw = t.getRandomRowValue(database, state);
		Expression whereClause = generateWhereClauseThatContainsRowValue(columns, rw);
		selectStatement.setWhereClause(whereClause);
		List<Expression> groupByClause = generateGroupByClause(columns);
		selectStatement.setGroupByClause(groupByClause);
		Expression limitClause = generateLimit();
		selectStatement.setLimitClause(limitClause);
		if (limitClause != null) {
			Expression offsetClause = generateOffset();
			selectStatement.setOffsetClause(offsetClause);
		}
		List<OrderingTerm> orderBy = generateOrderBy(columns, rw);
		selectStatement.setOrderByClause(orderBy);
		SQLite3Visitor visitor = new SQLite3Visitor();
		visitor.visit(selectStatement);
		String queryString = visitor.get();
		boolean isContainedIn = isContainedIn(state, rw, queryString);
		if (!isContainedIn) {
			state.logInconsistency();
			throw new Main.ReduceMeException();
		}
	}

	private Expression generateOffset() {
		if (Randomly.getBoolean()) {
			// OFFSET 0
			return Constant.createIntConstant(0);
		} else {
			return null;
		}
	}

	private boolean isContainedIn(StateToReproduce state, RowValue rw, String queryString) throws SQLException {
		Statement createStatement;
		createStatement = database.createStatement();

		StringBuilder sb = new StringBuilder();
		sb.append("SELECT ");
		String columnNames = rw.getRowValuesAsString();
		sb.append(columnNames);
		state.values = columnNames;
		sb.append(" INTERSECT SELECT * FROM ("); // ANOTHER SELECT TO USE ORDER BY without restrictions
		sb.append(queryString);
		sb.append(")");
		String resultingQueryString = sb.toString();
		state.query = resultingQueryString;
		try (ResultSet result = createStatement.executeQuery(resultingQueryString)) {
			boolean isContainedIn = !result.isClosed();
			createStatement.close();
			return isContainedIn;
		} catch (SQLException e) {
			if (shouldIgnoreException(e)) {
				return true;
			} else {
				throw e;
			}
		}
	}

	static boolean shouldIgnoreException(SQLException e) {
		return e.getMessage().contentEquals("[SQLITE_ERROR] SQL error or missing database (integer overflow)");
	}
	

	private List<OrderingTerm> generateOrderBy(List<Column> columns, RowValue rw) {
		List<OrderingTerm> orderBys = new ArrayList<>();
		for (int i = 0; i < Randomly.smallNumber(); i++) {
			Expression expr;
			expr = Constant.createTextConstant("asdf");
			Ordering order = Randomly.fromOptions(Ordering.ASC, Ordering.DESC);
			orderBys.add(new OrderingTerm(expr, order));
			// TODO RANDOM()
		}
		// TODO collate
		return orderBys;
	}

	private boolean integerConstantOutsideRange(Expression expr, int size) {
		if (!(expr instanceof Constant)) {
			return false;
		}
		Constant con = (Constant) expr;
		if (con.isNull()) {
			return false;
		}
		if (con.getDataType() == SQLite3DataType.INT) {
			long val = con.asInt();
			if (val < 1 || val > size) {
				return false;
			}
		}
		return true;
	}

	private Expression generateLimit() {
		if (Randomly.getBoolean()) {
			return Constant.createIntConstant(Integer.MAX_VALUE);
		} else {
			return null;
		}
	}

	private List<Expression> generateGroupByClause(List<Column> columns) {
		if (Randomly.getBoolean()) {
			return columns.stream().map(c -> new ColumnName(c)).collect(Collectors.toList());
		} else {
			return Collections.emptyList();
		}
	}

	private Expression generateWhereClauseThatContainsRowValue(List<Column> columns, RowValue rw) {

		Expression whereClause = generateNewExpression(columns, rw, true, 0);

		return whereClause;
	}

	private enum NewExpressionType {
		LITERAL, STANDALONE_COLUMN, DOUBLE_COLUMN, POSTFIX_COLUMN, NOT, UNARY_PLUS, AND, OR, COLLATE, UNARY_FUNCTION
	}

	private Expression generateNewExpression(List<Column> columns, RowValue rw, boolean shouldBeTrue, int depth) {
		if (depth >= Main.EXPRESSION_MAX_DEPTH) {
			return getStandaloneLiteral(shouldBeTrue);
		}
		if (Randomly.getBoolean() && shouldBeTrue) {
			return generateExpression(columns, rw);
		}
		boolean retry;
		do {
			retry = false;
			switch (Randomly.fromOptions(NewExpressionType.values())) {
			case STANDALONE_COLUMN:
				Expression expr = createStandaloneColumn(columns, rw, shouldBeTrue);
				if (expr == null) {
					retry = true;
					continue;
				} else {
					return expr;
				}
			case DOUBLE_COLUMN:
				Column c = Randomly.fromList(columns);
				Constant sampledConstant = rw.getValues().get(c);
				return createSampleBasedTwoColumnComparison(columns, rw, sampledConstant, new ColumnName(c),
						shouldBeTrue);
			case POSTFIX_COLUMN:
				c = Randomly.fromList(columns);
				sampledConstant = rw.getValues().get(c);
				return generateSampleBasedColumnPostfix(sampledConstant, new ColumnName(c), shouldBeTrue);
			case LITERAL:
				return getStandaloneLiteral(shouldBeTrue);
			case UNARY_FUNCTION:
				if (shouldBeTrue) {
					return getUnaryFunction();
				} else {
					retry = true;
					break;
				}
			case NOT:
				return new UnaryOperation(UnaryOperator.NOT,
						generateNewExpression(columns, rw, !shouldBeTrue, depth + 1));
			case UNARY_PLUS:
				return new UnaryOperation(UnaryOperator.PLUS,
						generateNewExpression(columns, rw, shouldBeTrue, depth + 1));
			case COLLATE:
				return new Expression.CollateOperation(generateNewExpression(columns, rw, shouldBeTrue, depth + 1),
						Randomly.fromOptions("NOCASE", "RTRIM", "BINARY"));
			case AND:
				Expression left;
				Expression right;
				left = generateNewExpression(columns, rw, shouldBeTrue, depth + 1);
				right = generateNewExpression(columns, rw, shouldBeTrue, depth + 1);
				if (shouldBeTrue) {
					return new Expression.BinaryOperation(left, right, BinaryOperator.AND);
				} else {
					return new Expression.BinaryOperation(left, right, BinaryOperator.OR);
				}
			case OR:
				Expression leftExpr = generateNewExpression(columns, rw, shouldBeTrue, depth + 1);
				Expression rightExpr;
				if (shouldBeTrue) {
					// one side can be false
					rightExpr = generateNewExpression(columns, rw, Randomly.getBoolean(), depth + 1);
					if (Randomly.getBoolean()) {
						// swap to allow leftExpr to be false
						Expression tmpExpression = leftExpr;
						leftExpr = rightExpr;
						rightExpr = tmpExpression;
					}
					return new Expression.BinaryOperation(leftExpr, rightExpr, BinaryOperator.OR);
				} else {
					rightExpr = generateNewExpression(columns, rw, shouldBeTrue, depth + 1);
					return new Expression.BinaryOperation(leftExpr, rightExpr, BinaryOperator.AND);
				}

			default:
				throw new AssertionError();
			}
		} while (retry);
		throw new AssertionError();
	}

	private Expression getUnaryFunction() {
		String functionName = Randomly.fromOptions("sqlite_source_id", "sqlite_version",
				"total_changes" /* some rows should have been inserted */);
		return new Expression.Function(functionName);
	}

	private Constant castToNumeric(Constant value) {
		// TODO complete and test
		switch (value.getDataType()) {
		case NULL:
		case INT:
		case REAL:
			return value;
		case TEXT:
			for (int i = value.asString().length(); i >= 0; i--) {
				try {
					double d = Double.valueOf(value.asString().substring(0, i));
					if (d == (int) d) {
						return Constant.createIntConstant((long) d);
					} else {
						return Constant.createRealConstant(d);
					}
				} catch (Exception e) {

				}
			}
			return Constant.createIntConstant(0);
		default:
			throw new AssertionError();
		}
	}

	// e.g., WHERE c0 or
	private Expression createStandaloneColumn(List<Column> columns, RowValue rw, boolean shouldBeTrue) {
		Column c = Randomly.fromList(columns);
		Constant value = rw.getValues().get(c);
		if (value.isNull()) {
			return null;
		}
		switch (value.getDataType()) {
		case INT:
			if (shouldBeTrue && value.asInt() != 0) {
				return new ColumnName(c);
			} else if (!shouldBeTrue && value.asInt() == 0) {
				return new ColumnName(c);
			} else {
				return null;
			}
		case REAL:
			// TODO: directly comparing to a double is probably not a good idea
			if (shouldBeTrue && value.asDouble() != 0.0) {
				return new ColumnName(c);
			} else if (!shouldBeTrue && value.asDouble() == 0.0) {
				return new ColumnName(c);
			} else {
				return null;
			}
		case TEXT:
			// TODO: false positive: a column condition was generated with value 0Xe.p9
			Constant numericConstant = castToNumeric(Constant.createTextConstant(value.asString()));
			if (numericConstant.getDataType() == SQLite3DataType.INT) {
				if (shouldBeTrue && numericConstant.asInt() != 0) {
					return new ColumnName(c);
				} else if (!shouldBeTrue && numericConstant.asInt() == 0) {
					return new ColumnName(c);
				} else {
					return null;
				}
			}
			return null;
		// case BINARY:
//			byte[] bytes = (byte[]) value;
//			for (int i = 0; i < bytes.length; i++) {
//				if (bytes[i] == '0' || bytes[i] == '+' || bytes[i] == '-') {
//					continue;
//				}
//				if (bytes[i] >= '1' && bytes[i] <= '9') {
//					if (shouldBeTrue) {
//						return new ColumnName(c);
//					} else {
//						return null;
//					}
//				} else {
//					if (!shouldBeTrue) {
//						return new ColumnName(c);
//					} else {
//						return null;
//					}
//				}
//			}
//			if (shouldBeTrue) {
//				return null;
//			} else {
//				// bytes is all zero or bytes.length == 0;
//				return new ColumnName(c);
//			}
		default:
			return null;

		}
	}

	private Expression getStandaloneLiteral(boolean shouldBeTrue) throws AssertionError {
		switch (Randomly.fromOptions(SQLite3DataType.INT, SQLite3DataType.TEXT, SQLite3DataType.REAL)) {
		case INT:
			// only a zero integer is false
			long value;
			if (shouldBeTrue) {
				value = Randomly.getNonZeroInteger();
			} else {
				value = 0;
			}
			return Constant.createIntConstant(value);
		case TEXT:
			String strValue;
			if (shouldBeTrue) {
				strValue = Long.toString(Randomly.getNonZeroInteger());
			} else {
				strValue = Randomly.fromOptions("0", "asdf", "c", "-a"); // TODO
			}
			return Constant.createTextConstant(strValue);
		case REAL:
			double realValue;
			if (shouldBeTrue) {
				realValue = Randomly.getNonZeroReal();
			} else {
				realValue = Randomly.fromOptions(0.0, -0.0);
			}
			return Constant.createRealConstant(realValue);
		default:
			throw new AssertionError();
		}
	}

	private Expression generateExpression(List<Column> columns, RowValue rw) throws AssertionError {
		Expression term;
		BinaryOperator operator = Randomly.fromOptions(BinaryOperator.EQUALS, BinaryOperator.GREATER_EQUALS,
				BinaryOperator.IS, BinaryOperator.SMALLER_EQUALS);
		if (Randomly.getBoolean()) {
			// generate opaque predicate
			Expression con;
			SQLite3DataType randomType = Randomly.fromOptions(SQLite3DataType.INT, SQLite3DataType.TEXT,
					SQLite3DataType.NULL);

			switch (randomType) {
			case INT:
				long val = Randomly.getInteger();
				con = Constant.createIntConstant(val);
				break;
			case TEXT:
				con = Constant.createTextConstant(Randomly.getString());
				break;
			case NULL:
				con = Constant.createNullConstant();
				operator = BinaryOperator.IS;
				break;
			default:
				throw new AssertionError();
			}

			if (Randomly.getBoolean()) {
				// apply function
				con = new Expression.Function(getRandomUnaryFunction(), con);
			}
			term = new Expression.BinaryOperation(con, con, operator);
		} else {
			// select column
			Column selectedColumn = Randomly.fromList(columns);
			Constant sampledConstant = rw.getValues().get(selectedColumn);

			Expression compareTo;
			BinaryOperator binaryOperator;
			SQLite3DataType valueType = sampledConstant.getDataType();

			Expression columnName = new Expression.ColumnName(selectedColumn);
			if (Randomly.getBoolean()) {
				term = generateSampleBasedColumnPostfix(sampledConstant, columnName, true);
			} else {
				if (sampledConstant.isNull()) {
					// is null, comparison with >= etc. yields false
					binaryOperator = BinaryOperator.IS;
					compareTo = Randomly.fromOptions(sampledConstant, columnName);
				} else if (Randomly.getBoolean()) {
					return createSampleBasedTwoColumnComparison(columns, rw, sampledConstant, columnName, true);
				} else {
					Tuple t = createSampleBasedColumnConstantComparison(sampledConstant, columnName);
					compareTo = t.expr;
					binaryOperator = t.op;
				}
				assert compareTo != null : binaryOperator;
				if (Randomly.getBoolean() && binaryOperator != BinaryOperator.LIKE
						&& binaryOperator != BinaryOperator.NOT_EQUALS) {
					String function = getPreservingFunctions(valueType);
					// apply function
					columnName = new Expression.Function(function, columnName);
					compareTo = new Expression.Function(function, compareTo);
				}
				term = new Expression.BinaryOperation(columnName, compareTo, binaryOperator);
			}
		}
		return term;
	}

	class Tuple {
		public Tuple(Expression compareTo, BinaryOperator binaryOperator) {
			this.op = binaryOperator;
			this.expr = compareTo;
		}

		BinaryOperator op;
		Expression expr;
	}

	private Tuple createSampleBasedColumnConstantComparison(Constant sampledConstant, Expression columnName) {
		boolean retry;
		BinaryOperator binaryOperator;
		Expression compareTo;
		SQLite3DataType valueType = sampledConstant.getDataType();

		do {
			binaryOperator = Randomly.fromOptions(BinaryOperator.GREATER_EQUALS, BinaryOperator.SMALLER_EQUALS,
					BinaryOperator.IS, BinaryOperator.NOT_EQUALS, BinaryOperator.GREATER, BinaryOperator.SMALLER,
					BinaryOperator.LIKE);
			retry = false;
			switch (binaryOperator) {
			case EQUALS:
				compareTo = Randomly.fromOptions(sampledConstant, columnName);
				break;
			case GREATER_EQUALS:
				if (valueType == SQLite3DataType.INT) {
					compareTo = Randomly.fromOptions(sampledConstant, columnName,
							smallerOrEqualRandomConstant(sampledConstant));
				} else {
					compareTo = Randomly.fromOptions(sampledConstant, columnName);
				}
				break;
			case GREATER:
				compareTo = smallerRandomConstant(sampledConstant);
				if (compareTo == null) {
					retry = true;
				}
				break;
			case IS:
				compareTo = Randomly.fromOptions(sampledConstant, columnName);
				break;
			case LIKE:
				if (valueType == SQLite3DataType.TEXT) {
					StringBuilder sb = new StringBuilder();
					if (Randomly.getBoolean()) {
						sb.append("%");
					}
					String compareToConstant = sampledConstant.asString();
					sb.append(compareToConstant);
					if (Randomly.getBoolean()) {
						sb.append("%");
					}
					compareTo = Constant.createTextConstant(sb.toString());
				} else {
					retry = true;
					compareTo = null;
				}
				break;
			case SMALLER:
				compareTo = greaterRandomConstant(sampledConstant);
				if (compareTo == null) {
					retry = true;
				}
				break;
			case SMALLER_EQUALS:
				if (valueType == SQLite3DataType.INT || valueType == SQLite3DataType.TEXT
						|| valueType == SQLite3DataType.REAL) {
					compareTo = Randomly.fromOptions(sampledConstant, columnName,
							greaterOrEqualRandomConstant(sampledConstant));
				} else {
					compareTo = Randomly.fromOptions(sampledConstant, columnName);
				}
				break;
			case NOT_EQUALS:
				compareTo = notEqualConstant(sampledConstant);
				break;
			default:
				throw new AssertionError(binaryOperator);
			}
		} while (retry);
		return new Tuple(compareTo, binaryOperator);
	}

	private Expression createSampleBasedTwoColumnComparison(List<Column> columns, RowValue rw, Constant sampledConstant,
			Expression columnName, boolean shouldBeTrue) throws AssertionError {
		BinaryOperator operator;
		// relate two columns
		Column otherColumn = Randomly.fromList(columns);
		Constant otherValue = rw.getValues().get(otherColumn);
		SQLite3DataType otherValueType = otherValue.getDataType();
		if (sampledConstant.getDataType() == otherValueType && sampledConstant.getDataType() == SQLite3DataType.INT) {
			long columnValue = sampledConstant.asInt();
			long otherColumnValue = otherValue.asInt();
			if (columnValue > otherColumnValue) {
				operator = Randomly.fromOptions(BinaryOperator.GREATER, BinaryOperator.GREATER_EQUALS,
						BinaryOperator.IS_NOT, BinaryOperator.NOT_EQUALS);
				// !shouldBeTrue and columnValue > otherColumnValue:
			} else if (columnValue < otherColumnValue) {
				operator = Randomly.fromOptions(BinaryOperator.SMALLER, BinaryOperator.SMALLER_EQUALS,
						BinaryOperator.IS_NOT, BinaryOperator.NOT_EQUALS);
			} else {
				operator = Randomly.fromOptions(BinaryOperator.EQUALS, BinaryOperator.IS, BinaryOperator.GREATER_EQUALS,
						BinaryOperator.SMALLER_EQUALS, BinaryOperator.GLOB);
			}
			if (!shouldBeTrue) {
				if (operator == BinaryOperator.GLOB) {
					// FIXME
					return getStandaloneLiteral(shouldBeTrue);
				}
				operator = operator.reverse();
			}
			if (Randomly.getBoolean()) {
				String functionName = getRandomFunction(operator, shouldBeTrue, sampledConstant.getDataType(),
						otherValueType);
				if (functionName != null) {
					Function left = new Expression.Function(functionName, columnName);
					Function right = new Expression.Function(functionName, new Expression.ColumnName(otherColumn));
					return new BinaryOperation(left, right, operator);

				}
			}
			return new BinaryOperation(columnName, new Expression.ColumnName(otherColumn), operator);
		} else if (sampledConstant.getDataType() == otherValueType
				&& sampledConstant.getDataType() == SQLite3DataType.REAL) {
			// duplicated, refactor
			double columnValue = sampledConstant.asDouble();
			double otherColumnValue = otherValue.asDouble();
			if (columnValue > otherColumnValue) {
				operator = Randomly.fromOptions(BinaryOperator.GREATER, BinaryOperator.GREATER_EQUALS,
						BinaryOperator.IS_NOT, BinaryOperator.NOT_EQUALS);
				// !shouldBeTrue and columnValue > otherColumnValue:
			} else if (columnValue < otherColumnValue) {
				operator = Randomly.fromOptions(BinaryOperator.SMALLER, BinaryOperator.SMALLER_EQUALS,
						BinaryOperator.IS_NOT, BinaryOperator.NOT_EQUALS);
			} else {
				operator = Randomly.fromOptions(BinaryOperator.EQUALS, BinaryOperator.IS, BinaryOperator.GREATER_EQUALS,
						BinaryOperator.SMALLER_EQUALS, BinaryOperator.GLOB);
			}
			if (!shouldBeTrue) {
				if (operator == BinaryOperator.GLOB) {
					// FIXME
					return getStandaloneLiteral(shouldBeTrue);
				}
				operator = operator.reverse();
			}
			if (Randomly.getBoolean()) {
				String functionName = getRandomFunction(operator, shouldBeTrue, sampledConstant.getDataType(),
						otherValueType);
				if (functionName != null) {
					Function left = new Expression.Function(functionName, columnName);
					Function right = new Expression.Function(functionName, new Expression.ColumnName(otherColumn));
					return new BinaryOperation(left, right, operator);

				}
			}
			return new BinaryOperation(columnName, new Expression.ColumnName(otherColumn), operator);
		} else {
			// FIXME: should not need this branch
			return getStandaloneLiteral(shouldBeTrue);
		}
	}

	enum BinaryFunction {

		ABS("abs")

		{
			@Override
			public boolean worksWhenApplied(BinaryOperator operator, SQLite3DataType leftType,
					SQLite3DataType rightType) {
				switch (operator) {
				case IS:
				case EQUALS:
				case GLOB:
				case LIKE:
					return true;
				case IS_NOT:
				case SMALLER:
				case SMALLER_EQUALS:
				case GREATER:
				case GREATER_EQUALS:
				case NOT_EQUALS: // abs(-5) = abs(5)
					return false;
				default:
					throw new AssertionError(operator);
				}
			}

		},
		UNLIKELY("unlikely") {

			@Override
			public boolean worksWhenApplied(BinaryOperator operator, SQLite3DataType leftType,
					SQLite3DataType rightType) {
				return true;
			}

		},
		LIKELY("likely") {
			@Override
			public boolean worksWhenApplied(BinaryOperator operator, SQLite3DataType leftType,
					SQLite3DataType rightType) {
				return true;
			}
		};

		private final String name;

		public String getName() {
			return name;
		}

		private BinaryFunction(String name) {
			this.name = name;
		}

		public abstract boolean worksWhenApplied(Expression.BinaryOperation.BinaryOperator operator,
				SQLite3DataType leftType, SQLite3DataType rightType);

		public static List<BinaryFunction> getPossibleBinaryFunctionsForOperator(BinaryOperator operator,
				SQLite3DataType leftDataType, SQLite3DataType rightDataType) {
			return Stream.of(BinaryFunction.values())
					.filter(fun -> fun.worksWhenApplied(operator, leftDataType, rightDataType))
					.collect(Collectors.toList());
		}

	}

	private String getRandomFunction(BinaryOperator operator, boolean shouldBeTrue, SQLite3DataType leftDataType,
			SQLite3DataType rightDataType) {
		if (!shouldBeTrue) {
			operator = operator.reverse();
		}
		List<BinaryFunction> functions = BinaryFunction.getPossibleBinaryFunctionsForOperator(operator, leftDataType,
				rightDataType);

		if (!functions.isEmpty() && Randomly.getBoolean()) {
			return Randomly.fromList(functions).getName();
		}
//		if (shouldBeTrue) {
		switch (operator) {
		case EQUALS:
			return Randomly.fromOptions("CHAR", "HEX", "LENGTH", "LOWER", "LTRIM", "QUOTE", "RTRIM",
					"TRIM", "TYPEOF", "UNICODE", "UPPER");
		case GREATER_EQUALS:
		case SMALLER_EQUALS: // not for ABS: ABS(-1) >= ABS(1) does not hold
			// LENGTH(-1) >= LENGTH(1)
			// char(0) == NULL
			// quote and floating point ...
			// TRIMS don't work for floating point
			// UPPER and LOWER do not work for floating point
			if ((leftDataType != SQLite3DataType.INT && leftDataType != SQLite3DataType.REAL)
					|| (rightDataType != SQLite3DataType.INT && rightDataType != SQLite3DataType.REAL)) {
				return Randomly.fromOptions("LENGTH", "QUOTE", "LTRIM", "RTRIM", "TRIM", "UPPER", "LOWER",
						"TYPEOF");
			}
			return Randomly.fromOptions("LIKELY", "TYPEOF", "UNLIKELY");
		case NOT_EQUALS:
			return Randomly.fromOptions("QUOTE", "LIKELY", "UNLIKELY", "HEX");
		default:
			return null;
		}
	}

	public static String getRandomUnaryFunction() {
		return Randomly.fromOptions("ABS", "CHAR", "HEX", "LENGTH", "LIKELY", "LOWER", "LTRIM", "QUOTE", "ROUND",
				"RTRIM", "TRIM", "TYPEOF", "UNLIKELY", "UPPER"); // "ZEROBLOB" "UNICODE",
	}

	private String getPreservingFunctions(SQLite3DataType dataType) {
		switch (dataType) {
		case TEXT:
			return Randomly.fromOptions("LOWER", "LTRIM", "QUOTE", "RTRIM", "TRIM", "UPPER"); // "ABS", "CHAR", "HEX",
																								// "ZEROBLOB" "UNICODE",
		case INT:
			return Randomly.fromOptions("QUOTE", "ROUND"); // "ABS", "CHAR", "HEX", "ZEROBLOB" "UNICODE",
		default:
			return "";
		}

	}

	/**
	 * 
	 * Based on the value of sampledConstant, this method generates an expression
	 * based on a column c so that either "c ISNULL", "C NOT NULL", or "C NOTNULL"
	 * is generated.
	 * 
	 * @param sampledConstant
	 * @param columnName
	 * @return
	 */
	private Expression generateSampleBasedColumnPostfix(Constant sampledConstant, Expression columnName,
			boolean shouldbeTrue) {
		boolean generateIsNull = sampledConstant.isNull() && shouldbeTrue || !sampledConstant.isNull() && !shouldbeTrue;
		if (generateIsNull) {
			return new Expression.PostfixUnaryOperation(PostfixUnaryOperation.PostfixUnaryOperator.ISNULL, columnName);
		} else {
			if (Randomly.getBoolean()) {
				return new Expression.PostfixUnaryOperation(PostfixUnaryOperation.PostfixUnaryOperator.NOT_NULL,
						columnName);
			} else {
				return new Expression.PostfixUnaryOperation(PostfixUnaryOperation.PostfixUnaryOperator.NOTNULL,
						columnName);
			}
		}
	}

	private Constant notEqualConstant(Constant sampledConstant) {
		switch (sampledConstant.getDataType()) {
		case INT:
			return Constant.createIntConstant(Randomly.notEqualInt(sampledConstant.asInt()));
		case TEXT:
			return Constant.createTextConstant(sampledConstant.asString() + "asdf");
		case REAL:
			return Constant.createRealConstant(sampledConstant.asDouble() % 10 + 0.3);
		case BINARY:
			byte[] asBinary = sampledConstant.asBinary();
			byte[] newBytes = new byte[asBinary.length + 1];
			newBytes[asBinary.length] = 32; // TODO random
			return Constant.createBinaryConstant(newBytes);
		default:
			throw new AssertionError();

		}
	}

	private Constant greaterRandomConstant(Constant sampledConstant) {
		switch (sampledConstant.getDataType()) {
		case INT:
			long value = sampledConstant.asInt();
			if (value == Long.MAX_VALUE) {
				return null;
			} else {
				return Constant.createIntConstant(Randomly.greaterInt(value));
			}
		default:
			return null;
		}
	}

	private Constant smallerRandomConstant(Constant sampledConstant) {
		switch (sampledConstant.getDataType()) {
		case INT:
			long value = sampledConstant.asInt();
			if (value == Long.MIN_VALUE) {
				return null;
			} else {
				return Constant.createIntConstant(Randomly.smallerInt(value));
			}
		default:
			return null;
		}
	}

	private Constant smallerOrEqualRandomConstant(Constant sampledConstant) {
		switch (sampledConstant.getDataType()) {
		case INT:
			long value = sampledConstant.asInt();
			return Constant.createIntConstant(Randomly.smallerOrEqualInt(value));
		default:
			return sampledConstant;
		}
	}

	private Constant greaterOrEqualRandomConstant(Constant sampledConstant) {
		switch (sampledConstant.getDataType()) {
		case INT:
			long value = sampledConstant.asInt();
			return Constant.createIntConstant(Randomly.greaterOrEqualInt(value));
		case TEXT:
			String strValue = sampledConstant.asString();
			return Constant.createTextConstant(Randomly.greaterOrEqualString(strValue));
		case REAL:
			return Constant.createRealConstant(Randomly.greaterOrEqualDouble(sampledConstant.asDouble()));
		default:
			return sampledConstant;
		}
	}

}
