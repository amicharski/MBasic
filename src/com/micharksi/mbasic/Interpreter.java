package com.micharksi.mbasic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Interpreter implements Expr.Visitor<Object>,
        Stmt.Visitor<Void> {

    final Environment globals = new Environment();
    private Environment environment = globals;


    private final Map<Expr, Integer> locals = new HashMap<>();

    Interpreter() {
        globals.define("clock", new MBasicCallable() {
            @Override
            public int arity() { return 0; }

            @Override
            public Object call(Interpreter interpreter,
                               List<Object> arguments) {
                return (double)System.currentTimeMillis() / 1000.0;
            }

            @Override
            public String toString() { return "<native fn>"; }
        });

        globals.define("print", new MBasicCallable() {

            @Override
            public int arity() {
                return 1;
            }

            @Override
            public Object call(Interpreter interpreter, List<Object> arguments) {
                System.out.println(arguments.get(0));
                return null;
            }
        });
    }

    void interpret(List<Stmt> statements) {
        try {
            for (Stmt statement : statements) {
                execute(statement);
            }
        } catch (RuntimeError error) {
            MBasic.runtimeError(error);
        }
    }


    private Object evaluate(Expr expr) {
        return expr.accept(this);
    }


    private void execute(Stmt stmt) {
        stmt.accept(this);
    }


    void resolve(Expr expr, int depth) {
        locals.put(expr, depth);
    }


    void executeBlock(List<Stmt> statements,
                      Environment environment) {
        Environment previous = this.environment;
        try {
            this.environment = environment;

            for (Stmt statement : statements) {
                execute(statement);
            }
        } finally {
            this.environment = previous;
        }
    }

    @Override
    public Object visitAssignExpr(Expr.Assign expr) {
        Object value = evaluate(expr.value);
/* Statements and State visit-assign < Resolving and Binding resolved-assign
    environment.assign(expr.name, value);
*/


        Integer distance = locals.get(expr);
        if (distance != null) {
            environment.assignAt(distance, expr.name, value);
        } else {
            globals.assign(expr.name, value);
        }


        return value;
    }

    @Override
    public Object visitBinaryExpr(Expr.Binary expr) {
        Object left = evaluate(expr.left);
        Object right = evaluate(expr.right); // [left]

        switch (expr.operator.type) {

            case BANG_EQUAL: return !isEqual(left, right);
            case EQUAL_EQUAL: return isEqual(left, right);


            case GREATER:

                checkNumberOperands(expr.operator, left, right);

                return (double)left > (double)right;
            case GREATER_EQUAL:

                checkNumberOperands(expr.operator, left, right);

                return (double)left >= (double)right;
            case LESS:

                checkNumberOperands(expr.operator, left, right);

                return (double)left < (double)right;
            case LESS_EQUAL:

                checkNumberOperands(expr.operator, left, right);

                return (double)left <= (double)right;

            case MINUS:

                checkNumberOperands(expr.operator, left, right);

                return (double)left - (double)right;

            case PLUS:
                if (left instanceof Double && right instanceof Double) {
                    return (double)left + (double)right;
                } // [plus]

                if (left instanceof String && right instanceof String) {
                    return (String)left + (String)right;
                }

/* Evaluating Expressions binary-plus < Evaluating Expressions string-wrong-type
        break;
*/

                throw new RuntimeError(expr.operator,
                        "Operands must be two numbers or two strings.");


            case SLASH:

                checkNumberOperands(expr.operator, left, right);

                return (double)left / (double)right;
            case STAR:

                checkNumberOperands(expr.operator, left, right);

                return (double)left * (double)right;
        }

        // Unreachable.
        return null;
    }

    @Override
    public Object visitCallExpr(Expr.Call expr) {
        Object callee = evaluate(expr.callee);

        List<Object> arguments = new ArrayList<>();
        for (Expr argument : expr.arguments) { // [in-order]
            arguments.add(evaluate(argument));
        }


        if (!(callee instanceof MBasicCallable)) {
            throw new RuntimeError(expr.paren,
                    "Can only call functions and classes.");
        }


        MBasicCallable function = (MBasicCallable)callee;

        if (arguments.size() != function.arity()) {
            throw new RuntimeError(expr.paren, "Expected " +
                    function.arity() + " arguments but got " +
                    arguments.size() + ".");
        }


        return function.call(this, arguments);
    }

    @Override
    public Object visitGroupingExpr(Expr.Grouping expr) {
        return evaluate(expr.expression);
    }

    @Override
    public Object visitLiteralExpr(Expr.Literal expr) {
        return expr.value;
    }

    @Override
    public Object visitLogicalExpr(Expr.Logical expr) {
        Object left = evaluate(expr.left);

        if (expr.operator.type == TokenType.LOGICAL_OR) {
            if (isTruthy(left)) return left;
        } else {
            if (!isTruthy(left)) return left;
        }

        return evaluate(expr.right);
    }

    @Override
    public Object visitUnaryExpr(Expr.Unary expr) {
        Object right = evaluate(expr.right);

        switch (expr.operator.type) {

            case BANG:
                return !isTruthy(right);

            case MINUS:

                checkNumberOperand(expr.operator, right);

                return -(double)right;
        }

        // Unreachable.
        return null;
    }

    @Override
    public Object visitVariableExpr(Expr.Variable expr) {
/* Statements and State visit-variable < Resolving and Binding call-look-up-variable
    return environment.get(expr.name);
*/

        return lookUpVariable(expr.name, expr);

    }

    private Object lookUpVariable(Token name, Expr expr) {
        Integer distance = locals.get(expr);
        if (distance != null) {
            return environment.getAt(distance, name.lexeme);
        } else {
            return globals.get(name);
        }
    }

    @Override
    public Void visitBlockStmt(Stmt.Block stmt) {
        executeBlock(stmt.statements, new Environment(environment));
        return null;
    }

    @Override
    public Void visitExpressionStmt(Stmt.Expression stmt) {
        evaluate(stmt.expression);
        return null;
    }

    @Override
    public Void visitFunctionStmt(Stmt.Function stmt) {
        MBasicFunction function = new MBasicFunction(stmt, environment,
                false);

        environment.define(stmt.name.lexeme, function);
        return null;
    }

    @Override
    public Void visitIfStmt(Stmt.If stmt) {
        if (isTruthy(evaluate(stmt.condition))) {
            execute(stmt.thenBranch);
        } else if (stmt.elseBranch != null) {
            execute(stmt.elseBranch);
        }
        return null;
    }

    @Override
    public Void visitNamespaceStmt(Stmt.Namespace stmt) {
        return null;
    }

    @Override
    public Void visitReturnStmt(Stmt.Return stmt) {
        Object value = null;
        if (stmt.value != null) value = evaluate(stmt.value);

        throw new Return(value);
    }

    @Override
    public Void visitVarStmt(Stmt.Var stmt) {
        Object value = null;
        if (stmt.initializer != null) {
            value = evaluate(stmt.initializer);
        }

        environment.define(stmt.name.lexeme, value);
        return null;
    }

    private void checkNumberOperand(Token operator, Object operand) {
        if (operand instanceof Double) return;
        throw new RuntimeError(operator, "Operand must be a number.");
    }


    private void checkNumberOperands(Token operator,
                                     Object left, Object right) {
        if (left instanceof Double && right instanceof Double) return;
        // [operand]
        throw new RuntimeError(operator, "Operands must be numbers.");
    }


    private boolean isTruthy(Object object) {
        if (object == null) return false;
        if (object instanceof Boolean) return (boolean)object;
        return true;
    }


    private boolean isEqual(Object a, Object b) {
        if (a == null && b == null) return true;
        if (a == null) return false;

        return a.equals(b);
    }


    private String stringify(Object object) {
        if (object == null) return "nil";

        if (object instanceof Double) {
            String text = object.toString();
            if (text.endsWith(".0")) {
                text = text.substring(0, text.length() - 2);
            }
            return text;
        }

        return object.toString();
    }
}
