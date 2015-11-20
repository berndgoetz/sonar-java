/*
 * SonarQube Java
 * Copyright (C) 2012 SonarSource
 * sonarqube@googlegroups.com
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
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02
 */
package org.sonar.java.se;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.sonar.java.collections.AVLTree;
import org.sonar.java.collections.PMap;
import org.sonar.plugins.java.api.semantic.Symbol;

import java.util.Collections;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class ProgramState {

  private int hashCode;

  public static final ProgramState EMPTY_STATE = new ProgramState(
    Maps.<Symbol, SymbolicValue>newHashMap(),
    /* Empty state knows that null literal is null */
    ImmutableMap.<SymbolicValue, Object>builder()
      .put(SymbolicValue.NULL_LITERAL, ConstraintManager.NullConstraint.NULL)
      .put(SymbolicValue.TRUE_LITERAL, ConstraintManager.BooleanConstraint.TRUE)
      .put(SymbolicValue.FALSE_LITERAL, ConstraintManager.BooleanConstraint.FALSE)
      .build(),
    AVLTree.<ExplodedGraph.ProgramPoint, Integer>create(),
    Lists.<SymbolicValue>newLinkedList());

  final PMap<ExplodedGraph.ProgramPoint, Integer> visitedPoints;
  final Deque<SymbolicValue> stack;
  Map<Symbol, SymbolicValue> values;
  Map<SymbolicValue, Object> constraints;

  public ProgramState(Map<Symbol, SymbolicValue> values, Map<SymbolicValue, Object> constraints, PMap<ExplodedGraph.ProgramPoint, Integer> visitedPoints,
    Deque<SymbolicValue> stack) {
    this.values = ImmutableMap.copyOf(values);
    this.constraints = ImmutableMap.copyOf(constraints);
    this.visitedPoints = visitedPoints;
    this.stack = stack;
  }

  static ProgramState stackValue(ProgramState ps, SymbolicValue sv) {
    Deque<SymbolicValue> newStack = new LinkedList<>(ps.stack);
    newStack.push(sv);
    return new ProgramState(ps.values, ps.constraints, ps.visitedPoints, newStack);
  }

  static Pair<ProgramState, List<SymbolicValue>> unstack(ProgramState programState, int nbElements) {
    if (nbElements == 0) {
      return new Pair<>(programState, Collections.<SymbolicValue>emptyList());
    }
    Preconditions.checkArgument(programState.stack.size() >= nbElements, nbElements);
    Deque<SymbolicValue> newStack = new LinkedList<>(programState.stack);
    List<SymbolicValue> result = Lists.newArrayList();
    for (int i = 0; i < nbElements; i++) {
      result.add(newStack.pop());
    }
    return new Pair<>(new ProgramState(programState.values, programState.constraints, programState.visitedPoints, newStack), result);
  }

  static ProgramState put(ProgramState programState, Symbol symbol, SymbolicValue value) {
    if (symbol.isUnknown()) {
      return programState;
    }
    SymbolicValue symbolicValue = programState.values.get(symbol);
    // update program state only for a different symbolic value
    if (symbolicValue == null || !symbolicValue.equals(value)) {
      Map<Symbol, SymbolicValue> temp = Maps.newHashMap(programState.values);
      temp.put(symbol, value);
      return new ProgramState(temp, programState.constraints, programState.visitedPoints, programState.stack);
    }
    return programState;
  }

  public SymbolicValue peekValue() {
    return stack.peek();
  }

  int numberOfTimeVisited(ExplodedGraph.ProgramPoint programPoint) {
    Integer count = visitedPoints.get(programPoint);
    return count == null ? 0 : count;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    ProgramState that = (ProgramState) o;
    return Objects.equals(values, that.values) &&
      Objects.equals(constraints, that.constraints) &&
      Objects.equals(peekValue(), that.peekValue());
  }

  @Override
  public int hashCode() {
    if (hashCode == 0) {
      hashCode = Objects.hash(values, constraints, peekValue());
    }
    return hashCode;
  }

  @Override
  public String toString() {
    return "{" + values.toString() + "}  {" + constraints.toString() + "}" + " { " + stack.toString() + " }";
  }

}
