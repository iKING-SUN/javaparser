/*
 * Copyright 2016 Federico Tomassetti
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.javaparser.symbolsolver.javaparsermodel.contexts;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.resolution.declarations.ResolvedValueDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedTypeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedTypeParameterDeclaration;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.core.resolution.Context;
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade;
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFactory;
import com.github.javaparser.symbolsolver.model.resolution.SymbolReference;
import com.github.javaparser.symbolsolver.model.resolution.TypeSolver;
import com.github.javaparser.symbolsolver.model.resolution.Value;
import com.github.javaparser.symbolsolver.reflectionmodel.ReflectionClassDeclaration;
import com.github.javaparser.symbolsolver.resolution.SymbolDeclarator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static com.github.javaparser.symbolsolver.javaparser.Navigator.getParentNode;

/**
 * @author Federico Tomassetti
 */
public abstract class AbstractJavaParserContext<N extends Node> implements Context {

    protected N wrappedNode;
    protected TypeSolver typeSolver;

    ///
    /// Static methods
    ///

    public static final SymbolReference<ResolvedValueDeclaration> solveWith(SymbolDeclarator symbolDeclarator, String name) {
        for (ResolvedValueDeclaration decl : symbolDeclarator.getSymbolDeclarations()) {
            if (decl.getName().equals(name)) {
                return SymbolReference.solved(decl);
            }
        }
        return SymbolReference.unsolved(ResolvedValueDeclaration.class);
    }

    ///
    /// Constructors
    ///

    public AbstractJavaParserContext(N wrappedNode, TypeSolver typeSolver) {
        if (wrappedNode == null) {
            throw new NullPointerException();
        }
        this.wrappedNode = wrappedNode;
        this.typeSolver = typeSolver;
    }

    ///
    /// Public methods
    ///

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        AbstractJavaParserContext<?> that = (AbstractJavaParserContext<?>) o;

        if (wrappedNode != null ? !wrappedNode.equals(that.wrappedNode) : that.wrappedNode != null) return false;

        return true;
    }

    @Override
    public int hashCode() {
        return wrappedNode != null ? wrappedNode.hashCode() : 0;
    }

    @Override
    public Optional<ResolvedType> solveGenericType(String name, TypeSolver typeSolver) {
        Context parent = getParent();
        if (parent == null) {
            return Optional.empty();
        } else {
            return parent.solveGenericType(name, typeSolver);
        }
    }

    @Override
    public final Context getParent() {
        if (getParentNode(wrappedNode) instanceof MethodCallExpr) {
            MethodCallExpr parentCall = (MethodCallExpr) getParentNode(wrappedNode);
            boolean found = false;
            if (parentCall.getArguments() != null) {
                for (Expression expression : parentCall.getArguments()) {
                    if (expression == wrappedNode) {
                        found = true;
                    }
                }
            }
            if (found) {
                Node notMethod = getParentNode(wrappedNode);
                while (notMethod instanceof MethodCallExpr) {
                    notMethod = getParentNode(notMethod);
                }
                return JavaParserFactory.getContext(notMethod, typeSolver);
            }
        }
        Node notMethod = getParentNode(wrappedNode);
        while (notMethod instanceof MethodCallExpr || notMethod instanceof FieldAccessExpr) {
            notMethod = getParentNode(notMethod);
        }
        if (notMethod == null) {
            return null;
        }
        return JavaParserFactory.getContext(notMethod, typeSolver);
    }

    ///
    /// Protected methods
    ///

    protected Optional<Value> solveWithAsValue(SymbolDeclarator symbolDeclarator, String name, TypeSolver typeSolver) {
        return symbolDeclarator.getSymbolDeclarations().stream()
                .filter(d -> d.getName().equals(name))
                .map(d -> Value.from(d))
                .findFirst();
    }

    protected Collection<ResolvedReferenceTypeDeclaration> findTypeDeclarations(Optional<Expression> optScope, TypeSolver typeSolver) {
        if (optScope.isPresent()) {
            Expression scope = optScope.get();

            // consider static methods
            if (scope instanceof NameExpr) {
                NameExpr scopeAsName = (NameExpr) scope;
                SymbolReference<ResolvedTypeDeclaration> symbolReference = this.solveType(scopeAsName.getName().getId(), typeSolver);
                if (symbolReference.isSolved() && symbolReference.getCorrespondingDeclaration().isType()) {
                    return Arrays.asList(symbolReference.getCorrespondingDeclaration().asReferenceType());
                }
            }

            ResolvedType typeOfScope = null;
            try {
                typeOfScope = JavaParserFacade.get(typeSolver).getType(scope);
            } catch (Exception e) {
                throw new RuntimeException(String.format("Issue calculating the type of the scope of " + this), e);
            }
            if (typeOfScope.isWildcard()) {
                if (typeOfScope.asWildcard().isExtends() || typeOfScope.asWildcard().isSuper()) {
                    return Arrays.asList(typeOfScope.asWildcard().getBoundedType().asReferenceType().getTypeDeclaration());
                } else {
                    return Arrays.asList(new ReflectionClassDeclaration(Object.class, typeSolver).asReferenceType());
                }
            } else if (typeOfScope.isArray()) {
                // method call on array are Object methods
                return Arrays.asList(new ReflectionClassDeclaration(Object.class, typeSolver).asReferenceType());
            } else if (typeOfScope.isTypeVariable()) {
                Collection<ResolvedReferenceTypeDeclaration> result = new ArrayList<>();
                for (ResolvedTypeParameterDeclaration.Bound bound : typeOfScope.asTypeParameter().getBounds()) {
                    result.add(bound.getType().asReferenceType().getTypeDeclaration());
                }
                return result;
            } else if (typeOfScope.isConstraint()){
                return Arrays.asList(typeOfScope.asConstraintType().getBound().asReferenceType().getTypeDeclaration());
            } else {
                return Arrays.asList(typeOfScope.asReferenceType().getTypeDeclaration());
            }
        } else {
            ResolvedType typeOfScope = JavaParserFacade.get(typeSolver).getTypeOfThisIn(wrappedNode);
            return Arrays.asList(typeOfScope.asReferenceType().getTypeDeclaration());
        }
    }

}
