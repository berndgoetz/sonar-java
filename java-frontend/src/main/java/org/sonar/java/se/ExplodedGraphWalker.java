/*
 * SonarQube Java
 * Copyright (C) 2012-2016 SonarSource SA
 * mailto:contact AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.java.se;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;
import org.sonar.java.cfg.CFG;
import org.sonar.java.cfg.LiveVariables;
import org.sonar.java.matcher.MethodMatcher;
import org.sonar.java.model.JavaTree;
import org.sonar.java.se.checks.ConditionAlwaysTrueOrFalseCheck;
import org.sonar.java.se.checks.LocksNotUnlockedCheck;
import org.sonar.java.se.checks.NoWayOutLoopCheck;
import org.sonar.java.se.checks.NonNullSetToNullCheck;
import org.sonar.java.se.checks.NullDereferenceCheck;
import org.sonar.java.se.checks.SECheck;
import org.sonar.java.se.checks.UnclosedResourcesCheck;
import org.sonar.java.se.constraint.ConstraintManager;
import org.sonar.java.se.constraint.ObjectConstraint;
import org.sonar.java.se.crossprocedure.MethodBehavior;
import org.sonar.java.se.crossprocedure.MethodBehaviorRoster;
import org.sonar.java.se.crossprocedure.MethodInvocationYield;
import org.sonar.java.se.symbolicvalues.SymbolicExceptionValue;
import org.sonar.java.se.symbolicvalues.SymbolicValue;
import org.sonar.plugins.java.api.JavaFileScanner;
import org.sonar.plugins.java.api.semantic.Symbol;
import org.sonar.plugins.java.api.semantic.SymbolMetadata;
import org.sonar.plugins.java.api.semantic.Type;
import org.sonar.plugins.java.api.tree.ArrayAccessExpressionTree;
import org.sonar.plugins.java.api.tree.AssignmentExpressionTree;
import org.sonar.plugins.java.api.tree.BaseTreeVisitor;
import org.sonar.plugins.java.api.tree.BinaryExpressionTree;
import org.sonar.plugins.java.api.tree.BlockTree;
import org.sonar.plugins.java.api.tree.ConditionalExpressionTree;
import org.sonar.plugins.java.api.tree.DoWhileStatementTree;
import org.sonar.plugins.java.api.tree.ExpressionTree;
import org.sonar.plugins.java.api.tree.ForStatementTree;
import org.sonar.plugins.java.api.tree.IdentifierTree;
import org.sonar.plugins.java.api.tree.IfStatementTree;
import org.sonar.plugins.java.api.tree.LiteralTree;
import org.sonar.plugins.java.api.tree.MemberSelectExpressionTree;
import org.sonar.plugins.java.api.tree.MethodInvocationTree;
import org.sonar.plugins.java.api.tree.MethodTree;
import org.sonar.plugins.java.api.tree.NewArrayTree;
import org.sonar.plugins.java.api.tree.NewClassTree;
import org.sonar.plugins.java.api.tree.ReturnStatementTree;
import org.sonar.plugins.java.api.tree.ThrowStatementTree;
import org.sonar.plugins.java.api.tree.Tree;
import org.sonar.plugins.java.api.tree.TryStatementTree;
import org.sonar.plugins.java.api.tree.TypeCastTree;
import org.sonar.plugins.java.api.tree.UnaryExpressionTree;
import org.sonar.plugins.java.api.tree.VariableTree;
import org.sonar.plugins.java.api.tree.WhileStatementTree;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

public class ExplodedGraphWalker extends BaseTreeVisitor {

  private static final String EQUALS_METHOD_NAME = "equals";
  /**
   * Arbitrary number to limit symbolic execution.
   */
  private static final int MAX_STEPS = 10000;
  public static final int MAX_NESTED_BOOLEAN_STATES = 10000;
  private static final Logger LOG = Loggers.get(ExplodedGraphWalker.class);
  private static final Set<String> THIS_SUPER = ImmutableSet.of("this", "super");
  private static final boolean DEBUG_MODE_ACTIVATED = false;
  private static final int MAX_EXEC_PROGRAM_POINT = 2;
  private static final MethodMatcher SYSTEM_EXIT_MATCHER = MethodMatcher.create().typeDefinition("java.lang.System").name("exit").addParameter("int");
  private static final MethodMatcher OBJECT_WAIT_MATCHER = MethodMatcher.create().typeDefinition("java.lang.Object").name("wait").withNoParameterConstraint();

  private final ConditionAlwaysTrueOrFalseCheck alwaysTrueOrFalseChecker;
  private MethodTree methodTree;
  private ExplodedGraph explodedGraph;
  private Deque<ExplodedGraph.Node> workList;
  ExplodedGraph.Node node;
  ExplodedGraph.ProgramPoint programPosition;
  ProgramState programState;
  private LiveVariables liveVariables;
  private CheckerDispatcher checkerDispatcher;

  @VisibleForTesting
  int steps;
  ConstraintManager constraintManager;
  private boolean cleanup = true;
  private final MethodBehaviorRoster behaviorRoster;
  private final MethodBehavior methodBehavior;
  private ExceptionGraphWalker exceptionWalker;

  public static class ExplodedGraphTooBigException extends RuntimeException {
    public ExplodedGraphTooBigException(String s) {
      super(s);
    }
  }

  public static class MaximumStepsReachedException extends RuntimeException {
    public MaximumStepsReachedException(String s) {
      super(s);
    }

    public MaximumStepsReachedException(String s, TooManyNestedBooleanStatesException e) {
      super(s, e);
    }
  }

  public static class TooManyNestedBooleanStatesException extends RuntimeException {
  }

  @VisibleForTesting
  ExplodedGraphWalker(MethodBehavior methodBehavior, MethodBehaviorRoster referenceContainer) {
    this.methodBehavior = methodBehavior;
    this.behaviorRoster = referenceContainer;
    alwaysTrueOrFalseChecker = new ConditionAlwaysTrueOrFalseCheck();
    this.checkerDispatcher = new CheckerDispatcher(this, Lists.newArrayList(alwaysTrueOrFalseChecker, new NullDereferenceCheck(), new UnclosedResourcesCheck(),
      new LocksNotUnlockedCheck(), new NonNullSetToNullCheck(), new NoWayOutLoopCheck()));
  }

  @VisibleForTesting
  ExplodedGraphWalker(MethodBehavior methodBehavior, MethodBehaviorRoster referenceContainer, boolean cleanup) {
    this(methodBehavior, referenceContainer);
    this.cleanup = cleanup;
  }

  private ExplodedGraphWalker(MethodBehavior methodBehavior, MethodBehaviorRoster referenceContainer, ConditionAlwaysTrueOrFalseCheck alwaysTrueOrFalseChecker,
    List<SECheck> seChecks) {
    this.methodBehavior = methodBehavior;
    this.behaviorRoster = referenceContainer;
    this.alwaysTrueOrFalseChecker = alwaysTrueOrFalseChecker;
    this.checkerDispatcher = new CheckerDispatcher(this, seChecks);
  }

  @Override
  public void visitMethod(MethodTree tree) {
    BlockTree body = tree.block();
    if (body != null) {
      execute(tree);
    }
  }

  private void execute(MethodTree tree) {
    CFG cfg = CFG.build(tree);
    checkerDispatcher.init(tree, cfg);
    liveVariables = LiveVariables.analyze(cfg);
    explodedGraph = new ExplodedGraph();
    methodTree = tree;
    constraintManager = new ConstraintManager();
    workList = new LinkedList<>();
    if(DEBUG_MODE_ACTIVATED) {
      LOG.debug("Exploring Exploded Graph for method " + tree.simpleName().name() + " at line " + ((JavaTree) tree).getLine());
    }
    programState = ProgramState.EMPTY_STATE;
    steps = 0;
    exceptionWalker = new ExceptionGraphWalker(cfg);
    for (ProgramState startingState : startingStates(tree, programState)) {
      enqueue(new ExplodedGraph.ProgramPoint(cfg.entry(), 0), startingState);
    }
    while (!workList.isEmpty()) {
      steps++;
      if (steps > MAX_STEPS) {
        checkerDispatcher.executeExceptionEndOfExecution();
        throw new MaximumStepsReachedException("reached limit of " + MAX_STEPS + " steps for method " + tree.simpleName().name() + " in class " + tree.symbol().owner().name());
      }
      // LIFO:
      node = workList.removeFirst();
      programPosition = node.programPoint;
      programState = node.programState;
      if (programPosition.block.successors().isEmpty()) {
        handleEndOfExecutionPath(tree);
        LOG.debug("End of potential path reached!");
        continue;
      }
      try {
        if (programPosition.i < programPosition.block.elements().size()) {
          // process block element
          visit(programPosition.block.elements().get(programPosition.i), programPosition.block.terminator());
        } else if (programPosition.block.terminator() == null) {
          // process block exit, which is unconditional jump such as goto-statement or return-statement
          handleBlockExit(programPosition);
        } else if (programPosition.i == programPosition.block.elements().size()) {
          // process block exist, which is conditional jump such as if-statement
          checkerDispatcher.executeCheckPostStatement(programPosition.block.terminator());
        } else {
          // process branch
          // process block exist, which is conditional jump such as if-statement
          checkerDispatcher.executeCheckPreStatement(programPosition.block.terminator());
          handleBlockExit(programPosition);
        }
      } catch (ExplodedGraphWalker.TooManyNestedBooleanStatesException e) {
        checkerDispatcher.executeExceptionEndOfExecution();
        throw new MaximumStepsReachedException(
          "reached maximum number of " + MAX_NESTED_BOOLEAN_STATES + " branched states for method " + tree.simpleName().name() + " in class " + tree.symbol().owner().name(), e);
      }
    }

    checkerDispatcher.executeCheckEndOfExecution();
    // Cleanup:
    explodedGraph = null;
    workList = null;
    node = null;
    programState = null;
    constraintManager = null;
  }

  private void handleEndOfExecutionPath(MethodTree tree) {
    checkerDispatcher.executeCheckEndOfExecutionPath(constraintManager);
    if (methodBehavior.isConstructor()) {
      ProgramState state = programState.stackValue(constraintManager.createSymbolicValue(tree));
      state = state.addConstraint(state.peekValue(), ObjectConstraint.NOT_NULL);
      methodBehavior.addYield(state, constraintManager);
    } else if (methodBehavior.isVoidMethod()) {
      ProgramState state = programState.stackValue(SymbolicValue.NULL_LITERAL);
      methodBehavior.addYield(state, constraintManager);
    }
  }

  private Iterable<ProgramState> startingStates(MethodTree tree, ProgramState ps) {
    Iterable<ProgramState> startingStates = Lists.newArrayList(ps);
    boolean isEqualsMethod = EQUALS_METHOD_NAME.equals(tree.simpleName().name()) && tree.parameters().size() == 1;
    for (final VariableTree variableTree : tree.parameters()) {
      // create
      final SymbolicValue sv = constraintManager.createSymbolicValue(variableTree);
      final Symbol parameterSymbol = variableTree.symbol();
      startingStates = Iterables.transform(startingStates, new Function<ProgramState, ProgramState>() {
        @Override
        public ProgramState apply(ProgramState input) {
          return input.put(parameterSymbol, sv);
        }
      });
      methodBehavior.addParameter(parameterSymbol, sv);
      if (parameterCannotBeNull(parameterSymbol)) {
        startingStates = Iterables.transform(startingStates, new Function<ProgramState, ProgramState>() {
          @Override
          public ProgramState apply(ProgramState input) {
            return input.addConstraint(sv, ObjectConstraint.NOT_NULL);
          }
        });
      }
      if (isEqualsMethod || parameterCanBeNull(parameterSymbol)) {
        startingStates = Iterables.concat(Iterables.transform(startingStates, new Function<ProgramState, List<ProgramState>>() {
          @Override
          public List<ProgramState> apply(ProgramState input) {
            List<ProgramState> states = new ArrayList<>();
            states.addAll(sv.setConstraint(input, ObjectConstraint.nullConstraint(variableTree)));
            states.addAll(sv.setConstraint(input, ObjectConstraint.NOT_NULL));
            return states;
          }
        }));

      }
    }
    return startingStates;
  }

  private static boolean parameterCanBeNull(Symbol symbol) {
    final SymbolMetadata metadata = symbol.metadata();
    return metadata.isAnnotatedWith("javax.annotation.CheckForNull") || metadata.isAnnotatedWith("javax.annotation.Nullable");
  }

  private static boolean parameterCannotBeNull(Symbol symbol) {
    final SymbolMetadata metadata = symbol.metadata();
    return metadata.isAnnotatedWith("javax.annotation.Nonnull");
  }

  private void cleanUpProgramState(CFG.Block block) {
    if (cleanup) {
      Set<Symbol> symbolSet = methodBehavior.symbolSet();
      symbolSet.addAll(liveVariables.getOut(block));
      programState = programState.cleanupDeadSymbols(symbolSet);
      programState = programState.cleanupConstraints();
    }
  }

  private void handleBlockExit(ExplodedGraph.ProgramPoint programPosition) {
    CFG.Block block = programPosition.block;
    Tree terminator = block.terminator();
    cleanUpProgramState(block);
    if (terminator != null) {
      switch (terminator.kind()) {
        case IF_STATEMENT:
          handleBranch(block, cleanupCondition(((IfStatementTree) terminator).condition()));
          return;
        case CONDITIONAL_OR:
        case CONDITIONAL_AND:
          handleBranch(block, ((BinaryExpressionTree) terminator).leftOperand());
          return;
        case CONDITIONAL_EXPRESSION:
          handleBranch(block, ((ConditionalExpressionTree) terminator).condition());
          return;
        case FOR_STATEMENT:
          ExpressionTree condition = ((ForStatementTree) terminator).condition();
          if (condition != null) {
            handleBranch(block, condition, false);
            return;
          }
          break;
        case WHILE_STATEMENT:
          ExpressionTree whileCondition = ((WhileStatementTree) terminator).condition();
          handleBranch(block, cleanupCondition(whileCondition), !whileCondition.is(Tree.Kind.BOOLEAN_LITERAL));
          return;
        case DO_STATEMENT:
          ExpressionTree doCondition = ((DoWhileStatementTree) terminator).condition();
          handleBranch(block, cleanupCondition(doCondition), !doCondition.is(Tree.Kind.BOOLEAN_LITERAL));
          return;
        case SYNCHRONIZED_STATEMENT:
          resetFieldValues();
          break;
        case RETURN_STATEMENT:
          ProgramState ps = programState;
          if (methodBehavior.isConstructor()) {
            ps = ps.stackValue(constraintManager.createSymbolicValue(terminator));
          }
          ReturnStatementTree returnStatement = (ReturnStatementTree) terminator;
          if (returnStatement.expression() == null) {
            methodBehavior.addVoidYield(ps);
          } else {
            methodBehavior.addYield(ps, constraintManager);
          }
          break;
        case THROW_STATEMENT:
          ProgramState.Pop pop = programState.unstackValue(1);
          ProgramState state = pop.state.stackValue(constraintManager.createSymbolicExceptionValue((ThrowStatementTree) terminator));
          methodBehavior.addYield(state, constraintManager);
          break;
        default:
          // do nothing by default.
      }
    }
    // unconditional jumps, for-statement, switch-statement, synchronized:
    if (node.exitPath) {
      if (block.exitBlock() != null) {
        enqueue(new ExplodedGraph.ProgramPoint(block.exitBlock(), 0), programState, true);
      } else {
        for (CFG.Block successor : block.successors()) {
          enqueue(new ExplodedGraph.ProgramPoint(successor, 0), programState, true);
        }
      }

    } else {
      for (CFG.Block successor : block.successors()) {
        if (!block.isFinallyBlock() || isDirectFlowSuccessorOf(successor, block)) {
          enqueue(new ExplodedGraph.ProgramPoint(successor, 0), programState, successor == block.exitBlock());
        }
      }
    }
  }

  private static boolean isDirectFlowSuccessorOf(CFG.Block successor, CFG.Block block) {
    return successor != block.exitBlock() || successor.isMethodExitBlock();
  }

  /**
   * Required for accurate reporting.
   * If condition is && or || expression, then return its right operand.
   */
  private static Tree cleanupCondition(Tree condition) {
    if (condition.is(Tree.Kind.CONDITIONAL_AND, Tree.Kind.CONDITIONAL_OR)) {
      return ((BinaryExpressionTree) condition).rightOperand();
    }
    return condition;
  }

  private void handleBranch(CFG.Block programPosition, Tree condition) {
    handleBranch(programPosition, condition, true);
  }

  private void handleBranch(CFG.Block programPosition, Tree condition, boolean checkPath) {
    Pair<List<ProgramState>, List<ProgramState>> pair = constraintManager.assumeDual(programState);
    ExplodedGraph.ProgramPoint falseBlockProgramPoint = new ExplodedGraph.ProgramPoint(programPosition.falseBlock(), 0);
    for (ProgramState state : pair.a) {
      // enqueue false-branch, if feasible
      ProgramState ps = state.stackValue(SymbolicValue.FALSE_LITERAL);
      enqueue(falseBlockProgramPoint, ps, node.exitPath);
      if (checkPath) {
        alwaysTrueOrFalseChecker.evaluatedToFalse(condition);
      }
    }
    ExplodedGraph.ProgramPoint trueBlockProgramPoint = new ExplodedGraph.ProgramPoint(programPosition.trueBlock(), 0);
    for (ProgramState state : pair.b) {
      ProgramState ps = state.stackValue(SymbolicValue.TRUE_LITERAL);
      // enqueue true-branch, if feasible
      enqueue(trueBlockProgramPoint, ps, node.exitPath);
      if (checkPath) {
        alwaysTrueOrFalseChecker.evaluatedToTrue(condition);
      }
    }
  }

  private void visit(Tree tree, @Nullable Tree terminator) {
    if (!checkerDispatcher.executeCheckPreStatement(tree)) {
      // Some of the check pre statement sink the execution on this node.
      ProgramState state = programState.stackValue(constraintManager.createSymbolicExceptionValue(NullPointerException.class));
      methodBehavior.addYield(state, constraintManager);
      // methodBehavior.notifyExecutionSink();
      return;
    }
    switch (tree.kind()) {
      case METHOD_INVOCATION:
        MethodInvocationTree mit = (MethodInvocationTree) tree;
        if(SYSTEM_EXIT_MATCHER.matches(mit)) {
          //System exit is a sink of execution
          return;
        }
        List<ProgramState> results = executeMethodInvocation(mit);
        for (ProgramState ps : results) {
          programState = ps;
          checkerDispatcher.executeCheckPostStatement(tree);
        }
        clearStack(tree);
        return;
      case LABELED_STATEMENT:
      case SWITCH_STATEMENT:
      case EXPRESSION_STATEMENT:
      case PARENTHESIZED_EXPRESSION:
        throw new IllegalStateException("Cannot appear in CFG: " + tree.kind().name());
      case VARIABLE:
        executeVariable((VariableTree) tree, terminator);
        break;
      case TYPE_CAST:
        executeTypeCast((TypeCastTree) tree);
        break;
      case ASSIGNMENT:
      case MULTIPLY_ASSIGNMENT:
      case DIVIDE_ASSIGNMENT:
      case REMAINDER_ASSIGNMENT:
      case PLUS_ASSIGNMENT:
      case MINUS_ASSIGNMENT:
      case LEFT_SHIFT_ASSIGNMENT:
      case RIGHT_SHIFT_ASSIGNMENT:
      case UNSIGNED_RIGHT_SHIFT_ASSIGNMENT:
        executeAssignement((AssignmentExpressionTree) tree);
        break;
      case AND_ASSIGNMENT:
      case XOR_ASSIGNMENT:
      case OR_ASSIGNMENT:
        executeLogicalAssignement((AssignmentExpressionTree) tree);
        break;
      case ARRAY_ACCESS_EXPRESSION:
        executeArrayAccessExpression((ArrayAccessExpressionTree) tree);
        break;
      case NEW_ARRAY:
        executeNewArray((NewArrayTree) tree);
        break;
      case NEW_CLASS:
        executeNewClass((NewClassTree) tree);
        break;
      case MULTIPLY:
      case DIVIDE:
      case REMAINDER:
      case PLUS:
      case MINUS:
      case LEFT_SHIFT:
      case RIGHT_SHIFT:
      case UNSIGNED_RIGHT_SHIFT:
      case AND:
      case XOR:
      case OR:
      case GREATER_THAN:
      case GREATER_THAN_OR_EQUAL_TO:
      case LESS_THAN:
      case LESS_THAN_OR_EQUAL_TO:
      case EQUAL_TO:
      case NOT_EQUAL_TO:
        executeBinaryExpression(tree);
        break;
      case POSTFIX_INCREMENT:
      case POSTFIX_DECREMENT:
      case PREFIX_INCREMENT:
      case PREFIX_DECREMENT:
      case UNARY_MINUS:
      case UNARY_PLUS:
      case BITWISE_COMPLEMENT:
      case LOGICAL_COMPLEMENT:
      case INSTANCE_OF:
        executeUnaryExpression(tree);
        break;
      case IDENTIFIER:
        executeIdentifier((IdentifierTree) tree);
        break;
      case MEMBER_SELECT:
        executeMemberSelect((MemberSelectExpressionTree) tree);
        break;
      case INT_LITERAL:
      case LONG_LITERAL:
      case FLOAT_LITERAL:
      case DOUBLE_LITERAL:
      case CHAR_LITERAL:
      case STRING_LITERAL:
        SymbolicValue val = constraintManager.createSymbolicValue(tree);
        programState = programState.stackValue(val);
        programState = programState.addConstraint(val, ObjectConstraint.NOT_NULL);
        break;
      case BOOLEAN_LITERAL:
        boolean value = Boolean.parseBoolean(((LiteralTree) tree).value());
        programState = programState.stackValue(value ? SymbolicValue.TRUE_LITERAL : SymbolicValue.FALSE_LITERAL);
        break;
      case NULL_LITERAL:
        programState = programState.stackValue(SymbolicValue.NULL_LITERAL);
        break;
      case LAMBDA_EXPRESSION:
      case METHOD_REFERENCE:
        programState = programState.stackValue(constraintManager.createSymbolicValue(tree));
        break;
      default:
    }

    checkerDispatcher.executeCheckPostStatement(tree);
    clearStack(tree);
  }

  private List<ProgramState> executeMethodInvocation(MethodInvocationTree mit) {
    setSymbolicValueOnFields(mit);
    // unstack arguments and method identifier
    ProgramState.Pop unstack = programState.unstackValue(mit.arguments().size() + 1);
    programState = unstack.state;
    ProgramState state = programState;
    final SymbolicValue resultValue = constraintManager.createMethodSymbolicValue(mit, unstack.values);
    MethodBehavior behavior = behaviorRoster.getReference(mit.symbol());
    List<ProgramState> resultingStates = new ArrayList<>();
    if (behavior != null) {
      List<MethodInvocationYield> invocationYields = invocationYields(behavior, unstack, resultValue);
      resultingStates.addAll(resultStates(invocationYields, mit, unstack.state));
      if (resultingStates.isEmpty()) {
        reportNoYieldIssues(mit, invocationYields);
      }
      return resultingStates;
    }
    resultingStates.add(defaultResultState(mit, state, resultValue));
    return resultingStates;
  }

  private void reportNoYieldIssues(MethodInvocationTree mit, List<MethodInvocationYield> invocationYields) {
    List<String> messages = new ArrayList<>();
    for (MethodInvocationYield yield : invocationYields) {
      messages.addAll(yield.noYieldIssues(checkerDispatcher, mit));
    }
    if (messages.isEmpty()) {
      checkerDispatcher.reportIssue(mit, alwaysTrueOrFalseChecker, "Incompatible arguments in method call");
    } else {
      NullDereferenceCheck npeCheck = checkerDispatcher.getChecker(NullDereferenceCheck.class);
      for (String message : messages) {
        checkerDispatcher.reportIssue(mit, npeCheck, message);
      }
    }
  }

  private List<ProgramState> resultStates(List<MethodInvocationYield> invocationYields, MethodInvocationTree mit, ProgramState state) {
    List<ProgramState> yields = new ArrayList<>();
    for (MethodInvocationYield methodInvocationYield : invocationYields) {
      ProgramState yield = methodInvocationYield.compatibleState(mit, state);
      if (yield != null) {
        yields.add(yield);
      }
    }
    return yields;
  }

  private List<MethodInvocationYield> invocationYields(MethodBehavior behavior, ProgramState.Pop unstack, final SymbolicValue resultValue) {
    List<SymbolicValue> parameterValues = new ArrayList<>(unstack.values);
    parameterValues.remove(0);
    Collections.reverse(parameterValues);
    return behavior.invocationYields(parameterValues, resultValue, constraintManager, methodBehavior);
  }

  private ProgramState defaultResultState(MethodInvocationTree mit, ProgramState state, final SymbolicValue resultValue) {
    logState(mit);
    state = programState.stackValue(resultValue);
    if (isNonNullMethod(mit.symbol())) {
      return state.addConstraint(resultValue, ObjectConstraint.NOT_NULL);
    } else if (OBJECT_WAIT_MATCHER.matches(mit)) {
      return state.resetFieldValues(constraintManager);
    }
    return state;
  }

  private static boolean isNonNullMethod(Symbol symbol) {
    return !symbol.isUnknown() && symbol.metadata().isAnnotatedWith("javax.annotation.Nonnull");
  }

  private void executeVariable(VariableTree variableTree, @Nullable Tree terminator) {
    ExpressionTree initializer = variableTree.initializer();
    if (initializer == null) {
      SymbolicValue sv = null;
      if (terminator != null && terminator.is(Tree.Kind.FOR_EACH_STATEMENT)) {
        sv = constraintManager.createSymbolicValue(variableTree);
      } else if (variableTree.type().symbolType().is("boolean")) {
        sv = SymbolicValue.FALSE_LITERAL;
      } else if (!variableTree.type().symbolType().isPrimitive()) {
        sv = SymbolicValue.NULL_LITERAL;
      }
      if (sv != null) {
        programState = programState.put(variableTree.symbol(), sv);
      }
    } else {
      ProgramState.Pop unstack = programState.unstackValue(1);
      programState = unstack.state;
      programState = programState.put(variableTree.symbol(), unstack.values.get(0));
    }
  }

  private void executeTypeCast(TypeCastTree typeCast) {
    Type type = typeCast.type().symbolType();
    if (type.isPrimitive()) {
      ProgramState.Pop unstack = programState.unstackValue(1);
      programState = unstack.state;
      programState = programState.stackValue(constraintManager.createSymbolicValue(typeCast.expression()));
    }
  }

  private void executeAssignement(AssignmentExpressionTree tree) {
    ExpressionTree variable = tree.variable();
    if (variable.is(Tree.Kind.IDENTIFIER)) {
      // FIXME restricted to identifiers for now.
      ProgramState.Pop unstack = programState.unstackValue(2);
      SymbolicValue value = tree.is(Tree.Kind.ASSIGNMENT) ? unstack.values.get(1) : constraintManager.createSymbolicValue(tree);
      programState = unstack.state;
      programState = programState.put(((IdentifierTree) variable).symbol(), value);
      programState = programState.stackValue(value);
    }
  }

  private void executeLogicalAssignement(AssignmentExpressionTree tree) {
    ExpressionTree variable = tree.variable();
    if (variable.is(Tree.Kind.IDENTIFIER)) {
      ProgramState.Pop unstack = programState.unstackValue(2);
      SymbolicValue value = unstack.values.get(1);
      programState = unstack.state;
      Symbol variableSymbol = ((IdentifierTree) variable).symbol();
      SymbolicValue assignedTo = programState.getValue(variableSymbol);
      if (assignedTo == null) {
        assignedTo = constraintManager.createSymbolicValue(variable);
      }
      SymbolicValue symbolicValue = constraintManager.createSymbolicValue(tree);
      symbolicValue.computedFrom(ImmutableList.of(assignedTo, value));
      programState = programState.put(variableSymbol, symbolicValue);
      programState = programState.stackValue(symbolicValue);
    }
  }

  private void executeArrayAccessExpression(ArrayAccessExpressionTree tree) {
    // unstack expression and dimension
    ProgramState.Pop unstack = programState.unstackValue(2);
    programState = unstack.state;
    programState = programState.stackValue(constraintManager.createSymbolicValue(tree));
  }

  private void executeNewArray(NewArrayTree newArrayTree) {
    programState = programState.unstackValue(newArrayTree.initializers().size()).state;
    SymbolicValue svNewArray = constraintManager.createSymbolicValue(newArrayTree);
    programState = programState.stackValue(svNewArray);
    programState = svNewArray.setSingleConstraint(programState, ObjectConstraint.NOT_NULL);
  }

  private void executeNewClass(NewClassTree tree) {
    NewClassTree newClassTree = tree;
    programState = programState.unstackValue(newClassTree.arguments().size()).state;
    SymbolicValue svNewClass = constraintManager.createSymbolicValue(newClassTree);
    programState = programState.stackValue(svNewClass);
    programState = svNewClass.setSingleConstraint(programState, ObjectConstraint.NOT_NULL);
  }

  private void executeBinaryExpression(Tree tree) {
    // Consume two and produce one SV.
    ProgramState.Pop unstackBinary = programState.unstackValue(2);
    programState = unstackBinary.state;
    SymbolicValue symbolicValue = constraintManager.createSymbolicValue(tree);
    symbolicValue.computedFrom(unstackBinary.values);
    programState = programState.stackValue(symbolicValue);
  }

  private void executeUnaryExpression(Tree tree) {
    // consume one and produce one
    ProgramState.Pop unstackUnary = programState.unstackValue(1);
    programState = unstackUnary.state;
    SymbolicValue unarySymbolicValue = constraintManager.createSymbolicValue(tree);
    unarySymbolicValue.computedFrom(unstackUnary.values);
    if (tree.is(Tree.Kind.POSTFIX_DECREMENT, Tree.Kind.POSTFIX_INCREMENT, Tree.Kind.PREFIX_DECREMENT, Tree.Kind.PREFIX_INCREMENT)
      && ((UnaryExpressionTree) tree).expression().is(Tree.Kind.IDENTIFIER)) {
      programState = programState.put(((IdentifierTree) ((UnaryExpressionTree) tree).expression()).symbol(), unarySymbolicValue);
    }
    if (tree.is(Tree.Kind.POSTFIX_DECREMENT, Tree.Kind.POSTFIX_INCREMENT)) {
      programState = programState.stackValue(unstackUnary.values.get(0));
    } else {
      programState = programState.stackValue(unarySymbolicValue);
    }
  }

  private void executeIdentifier(IdentifierTree tree) {
    Symbol symbol = tree.symbol();
    SymbolicValue value = programState.getValue(symbol);
    if (value == null) {
      value = constraintManager.createSymbolicValue(tree);
      programState = programState.put(symbol, value);
    }
    programState = programState.stackValue(value);
  }

  private void executeMemberSelect(MemberSelectExpressionTree mse) {
    if (!"class".equals(mse.identifier().name())) {

      ProgramState.Pop unstackMSE = programState.unstackValue(1);
      programState = unstackMSE.state;
    }
    SymbolicValue mseValue = constraintManager.createSymbolicValue(mse);
    programState = programState.stackValue(mseValue);
  }

  public void clearStack(Tree tree) {
    if (tree.parent().is(Tree.Kind.EXPRESSION_STATEMENT)) {
      programState = programState.clearStack();
    }
  }

  private void setSymbolicValueOnFields(MethodInvocationTree tree) {
    if (isLocalMethodInvocation(tree)) {
      resetFieldValues();
    }
  }

  private static boolean isLocalMethodInvocation(MethodInvocationTree tree) {
    ExpressionTree methodSelect = tree.methodSelect();
    if (methodSelect.is(Tree.Kind.IDENTIFIER)) {
      return true;
    } else if (methodSelect.is(Tree.Kind.MEMBER_SELECT)) {
      MemberSelectExpressionTree memberSelectExpression = (MemberSelectExpressionTree) methodSelect;
      ExpressionTree target = memberSelectExpression.expression();
      if (target.is(Tree.Kind.IDENTIFIER)) {
        IdentifierTree identifier = (IdentifierTree) target;
        return THIS_SUPER.contains(identifier.name());
      }
    }
    return false;
  }

  private void resetFieldValues() {
    programState = programState.resetFieldValues(constraintManager);
  }

  private void logState(MethodInvocationTree mit) {
    if (mit.methodSelect().is(Tree.Kind.IDENTIFIER) && "printState".equals(((IdentifierTree) mit.methodSelect()).name())) {
      debugPrint(((JavaTree) mit).getLine(), node);
    }
  }

  private static void debugPrint(Object... toPrint) {
    if (DEBUG_MODE_ACTIVATED) {
      LOG.error(Joiner.on(" - ").join(toPrint));
    }
  }

  public void enqueue(ExplodedGraph.ProgramPoint programPoint, ProgramState programState) {
    enqueue(programPoint, programState, false);
  }

  public void enqueue(ExplodedGraph.ProgramPoint programPoint, ProgramState programState, boolean exitPath) {
    int nbOfExecution = programState.numberOfTimeVisited(programPoint);
    if (nbOfExecution > MAX_EXEC_PROGRAM_POINT) {
      debugPrint(programState);
      return;
    }
    checkExplodedGraphTooBig(programState);
    ExplodedGraph.Node cachedNode = explodedGraph.getNode(programPoint, programState.visitedPoint(programPoint, nbOfExecution + 1));
    if (!cachedNode.isNew && exitPath == cachedNode.exitPath) {
      // has been enqueued earlier
      return;
    }
    cachedNode.exitPath = exitPath;
    workList.addFirst(cachedNode);
  }

  private void checkExplodedGraphTooBig(ProgramState programState) {
    // Arbitrary formula to avoid out of memory errors
    if (steps + workList.size() > MAX_STEPS / 2 && programState.constraintsSize() > 75) {
      throw new ExplodedGraphTooBigException("Program state constraints are too big : stopping Symbolic Execution for method "
        + methodTree.simpleName().name() + " in class " + methodTree.symbol().owner().name());
    }
  }

  /**
   * This class ensures that the SE checks are placed in the correct order for the ExplodedGraphWalker
   * In addition, checks that are needed for a correct ExplodedGraphWalker processing are provided in all cases.
   *
   */
  public static class ExplodedGraphWalkerFactory {

    private final ConditionAlwaysTrueOrFalseCheck alwaysTrueOrFalseChecker;
    private final List<SECheck> seChecks = new ArrayList<>();

    public ExplodedGraphWalkerFactory(List<JavaFileScanner> scanners) {
      List<SECheck> checks = new ArrayList<>();
      for (JavaFileScanner scanner : scanners) {
        if (scanner instanceof SECheck) {
          checks.add((SECheck) scanner);
        }
      }
      alwaysTrueOrFalseChecker = removeOrDefault(checks, new ConditionAlwaysTrueOrFalseCheck());
      // This order of the mandatory SE checks is required by the ExplodedGraphWalker
      seChecks.add(alwaysTrueOrFalseChecker);
      seChecks.add(removeOrDefault(checks, new NullDereferenceCheck()));
      seChecks.add(removeOrDefault(checks, new UnclosedResourcesCheck()));
      seChecks.add(removeOrDefault(checks, new LocksNotUnlockedCheck()));
      seChecks.add(removeOrDefault(checks, new NonNullSetToNullCheck()));
      seChecks.add(removeOrDefault(checks, new NoWayOutLoopCheck()));
      seChecks.addAll(checks);
    }

    public ExplodedGraphWalker createWalker(MethodBehavior methodBehavior, MethodBehaviorRoster referenceContainer) {
      return new ExplodedGraphWalker(methodBehavior, referenceContainer, alwaysTrueOrFalseChecker, seChecks);
    }

    @SuppressWarnings("unchecked")
    private static <T extends SECheck> T removeOrDefault(List<SECheck> checks, T defaultInstance) {
      Iterator<SECheck> iterator = checks.iterator();
      while (iterator.hasNext()) {
        SECheck check = iterator.next();
        if (check.getClass().equals(defaultInstance.getClass())) {
          iterator.remove();
          return (T) check;
        }
      }
      return defaultInstance;
    }
  }

  public void notifyPotentialNullPointer(SymbolicValue value, MemberSelectExpressionTree syntaxNode) {
    methodBehavior.notifyPotentialNullPointer(value, syntaxNode);
  }

  public void processNextStep() {
    if (programState.peekValue() instanceof SymbolicExceptionValue) {
      processException((SymbolicExceptionValue) programState.peekValue());
      return;
    }
    if (programPosition.i < programPosition.block.elements().size()) {
      clearStack(programPosition.block.elements().get(programPosition.i));
    }
    enqueue(
      new ExplodedGraph.ProgramPoint(programPosition.block, programPosition.i + 1),
      programState, node.exitPath);
  }

  private void processException(SymbolicExceptionValue exceptionValue) {
    Tree location = programPosition.block.elements().get(programPosition.i);
    Tree parent = location.parent();
    while (parent != null) {
      if (parent.is(Tree.Kind.TRY_STATEMENT) && processNextCatch((TryStatementTree) parent, exceptionValue)) {
        break;
      }
      parent = parent.parent();
    }
    if (parent == null) {
      // Uncaught exception causes method exit: put the exception as method yield
      methodBehavior.addYield(programState, constraintManager);
      enqueue(
        new ExplodedGraph.ProgramPoint(exceptionWalker.lastBlock(), 0), programState, false);
    }
  }

  private boolean processNextCatch(TryStatementTree tryStatement, SymbolicExceptionValue exceptionValue) {
    CFG.Block block = exceptionWalker.catchBlock(tryStatement, exceptionValue);
    boolean exitPath = false;
    if (block == null) {
      block = exceptionWalker.finallyBlock(tryStatement);
      exitPath = true;
      if (block == null) {
        return false;
      }
    }
    enqueue(new ExplodedGraph.ProgramPoint(block, 0), programState, false);
    return true;
  }
}
