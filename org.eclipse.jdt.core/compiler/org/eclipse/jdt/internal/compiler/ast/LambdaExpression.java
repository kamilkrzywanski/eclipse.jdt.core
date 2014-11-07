/*******************************************************************************
 * Copyright (c) 2012, 2014 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *     Jesper S Moller - Contributions for
 *							bug 382701 - [1.8][compiler] Implement semantic analysis of Lambda expressions & Reference expression
 *							bug 382721 - [1.8][compiler] Effectively final variables needs special treatment
 *							Bug 416885 - [1.8][compiler]IncompatibleClassChange error (edit)
 *     Stephan Herrmann - Contribution for
 *							bug 401030 - [1.8][null] Null analysis support for lambda methods.
 *							Bug 392099 - [1.8][compiler][null] Apply null annotation on types for null analysis
 *							Bug 392238 - [1.8][compiler][null] Detect semantically invalid null type annotations
 *							Bug 400874 - [1.8][compiler] Inference infrastructure should evolve to meet JLS8 18.x (Part G of JSR335 spec)
 *							Bug 423504 - [1.8] Implement "18.5.3 Functional Interface Parameterization Inference"
 *							Bug 425142 - [1.8][compiler] NPE in ConstraintTypeFormula.reduceSubType
 *							Bug 425153 - [1.8] Having wildcard allows incompatible types in a lambda expression
 *							Bug 424205 - [1.8] Cannot infer type for diamond type with lambda on method invocation
 *							Bug 425798 - [1.8][compiler] Another NPE in ConstraintTypeFormula.reduceSubType
 *							Bug 425156 - [1.8] Lambda as an argument is flagged with incompatible error
 *							Bug 424403 - [1.8][compiler] Generic method call with method reference argument fails to resolve properly.
 *							Bug 426563 - [1.8] AIOOBE when method with error invoked with lambda expression as argument
 *							Bug 420525 - [1.8] [compiler] Incorrect error "The type Integer does not define sum(Object, Object) that is applicable here"
 *							Bug 427438 - [1.8][compiler] NPE at org.eclipse.jdt.internal.compiler.ast.ConditionalExpression.generateCode(ConditionalExpression.java:280)
 *							Bug 428294 - [1.8][compiler] Type mismatch: cannot convert from List<Object> to Collection<Object[]>
 *							Bug 428786 - [1.8][compiler] Inference needs to compute the "ground target type" when reducing a lambda compatibility constraint
 *							Bug 428980 - [1.8][null] simple expression as lambda body doesn't leverage null annotation on argument
 *							Bug 429430 - [1.8] Lambdas and method reference infer wrong exception type with generics (RuntimeException instead of IOException)
 *							Bug 432110 - [1.8][compiler] nested lambda type incorrectly inferred vs javac
 *							Bug 438458 - [1.8][null] clean up handling of null type annotations wrt type variables
 *							Bug 441693 - [1.8][null] Bogus warning for type argument annotated with @NonNull
 *     Andy Clement (GoPivotal, Inc) aclement@gopivotal.com - Contributions for
 *                          Bug 405104 - [1.8][compiler][codegen] Implement support for serializeable lambdas
 *******************************************************************************/
package org.eclipse.jdt.internal.compiler.ast;

import static org.eclipse.jdt.internal.compiler.ast.ExpressionContext.INVOCATION_CONTEXT;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import org.eclipse.jdt.core.compiler.CategorizedProblem;
import org.eclipse.jdt.core.compiler.CharOperation;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.internal.compiler.ASTVisitor;
import org.eclipse.jdt.internal.compiler.ClassFile;
import org.eclipse.jdt.internal.compiler.CompilationResult;
import org.eclipse.jdt.internal.compiler.IErrorHandlingPolicy;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileConstants;
import org.eclipse.jdt.internal.compiler.codegen.CodeStream;
import org.eclipse.jdt.internal.compiler.env.ICompilationUnit;
import org.eclipse.jdt.internal.compiler.flow.ExceptionHandlingFlowContext;
import org.eclipse.jdt.internal.compiler.flow.FlowContext;
import org.eclipse.jdt.internal.compiler.flow.FlowInfo;
import org.eclipse.jdt.internal.compiler.flow.UnconditionalFlowInfo;
import org.eclipse.jdt.internal.compiler.impl.CompilerOptions;
import org.eclipse.jdt.internal.compiler.impl.Constant;
import org.eclipse.jdt.internal.compiler.impl.ReferenceContext;
import org.eclipse.jdt.internal.compiler.lookup.AnnotationBinding;
import org.eclipse.jdt.internal.compiler.lookup.Binding;
import org.eclipse.jdt.internal.compiler.lookup.BlockScope;
import org.eclipse.jdt.internal.compiler.lookup.ClassScope;
import org.eclipse.jdt.internal.compiler.lookup.ExtraCompilerModifiers;
import org.eclipse.jdt.internal.compiler.lookup.InferenceContext18;
import org.eclipse.jdt.internal.compiler.lookup.IntersectionTypeBinding18;
import org.eclipse.jdt.internal.compiler.lookup.LocalVariableBinding;
import org.eclipse.jdt.internal.compiler.lookup.LookupEnvironment;
import org.eclipse.jdt.internal.compiler.lookup.MethodBinding;
import org.eclipse.jdt.internal.compiler.lookup.MethodScope;
import org.eclipse.jdt.internal.compiler.lookup.ParameterizedTypeBinding;
import org.eclipse.jdt.internal.compiler.lookup.PolyTypeBinding;
import org.eclipse.jdt.internal.compiler.lookup.ProblemMethodBinding;
import org.eclipse.jdt.internal.compiler.lookup.ProblemReasons;
import org.eclipse.jdt.internal.compiler.lookup.ReferenceBinding;
import org.eclipse.jdt.internal.compiler.lookup.Scope;
import org.eclipse.jdt.internal.compiler.lookup.SourceTypeBinding;
import org.eclipse.jdt.internal.compiler.lookup.SyntheticArgumentBinding;
import org.eclipse.jdt.internal.compiler.lookup.SyntheticMethodBinding;
import org.eclipse.jdt.internal.compiler.lookup.TagBits;
import org.eclipse.jdt.internal.compiler.lookup.TypeBinding;
import org.eclipse.jdt.internal.compiler.lookup.TypeConstants;
import org.eclipse.jdt.internal.compiler.lookup.TypeIds;
import org.eclipse.jdt.internal.compiler.lookup.VariableBinding;
import org.eclipse.jdt.internal.compiler.parser.Parser;
import org.eclipse.jdt.internal.compiler.problem.AbortCompilation;
import org.eclipse.jdt.internal.compiler.problem.AbortCompilationUnit;
import org.eclipse.jdt.internal.compiler.problem.AbortMethod;
import org.eclipse.jdt.internal.compiler.problem.AbortType;
import org.eclipse.jdt.internal.compiler.problem.ProblemSeverities;

@SuppressWarnings({"rawtypes", "unchecked"})
public class LambdaExpression extends FunctionalExpression implements IPolyExpression, ReferenceContext, ProblemSeverities {
	public Argument [] arguments;
	private TypeBinding [] argumentTypes;
	public int arrowPosition;
	public Statement body;
	public boolean hasParentheses;
	public MethodScope scope;
	boolean voidCompatible = true;
	boolean valueCompatible = false;
	boolean returnsValue;
	public boolean isSerializable;
	private boolean requiresGenericSignature;
	boolean returnsVoid;
	public LambdaExpression original = this;
	public SyntheticArgumentBinding[] outerLocalVariables = NO_SYNTHETIC_ARGUMENTS;
	private int outerLocalVariablesSlotSize = 0;
	public boolean shouldCaptureInstance = false;
	private boolean assistNode = false;
	private boolean hasIgnoredMandatoryErrors = false;
	private ReferenceBinding classType;
	public int ordinal;
	private Set thrownExceptions;
	public char[] text;  // source representation of the lambda.
	private static final SyntheticArgumentBinding [] NO_SYNTHETIC_ARGUMENTS = new SyntheticArgumentBinding[0];
	private static final Block NO_BODY = new Block(0);
	private HashMap<TypeBinding, LambdaExpression> copiesPerTargetType;
	protected Expression [] resultExpressions = NO_EXPRESSIONS;
	
	public LambdaExpression(CompilationResult compilationResult, boolean assistNode, boolean requiresGenericSignature) {
		super(compilationResult);
		this.assistNode = assistNode;
		this.requiresGenericSignature = requiresGenericSignature;
		setArguments(NO_ARGUMENTS);
		setBody(NO_BODY);
	}

	public LambdaExpression(CompilationResult compilationResult, boolean assistNode) {
		this(compilationResult, assistNode, false);
	}

	public void setArguments(Argument [] arguments) {
		this.arguments = arguments != null ? arguments : ASTNode.NO_ARGUMENTS;
		this.argumentTypes = new TypeBinding[arguments != null ? arguments.length : 0];
	}
	
	public Argument [] arguments() {
		return this.arguments;
	}

	public TypeBinding[] argumentTypes() {
		return this.argumentTypes;
	}

	public void setBody(Statement body) {
		this.body = body == null ? NO_BODY : body;
	}
	
	public Statement body() {
		return this.body;
	}

	public Expression[] resultExpressions() {
		return this.resultExpressions;
	}

	public void setArrowPosition(int arrowPosition) {
		this.arrowPosition = arrowPosition;
	}
	
	public int arrowPosition() {
		return this.arrowPosition;
	}
	
	protected FunctionalExpression original() {
		return this.original;
	}
	
	public void generateCode(BlockScope currentScope, CodeStream codeStream, boolean valueRequired) {
		if (this.shouldCaptureInstance) {
			this.binding.modifiers &= ~ClassFileConstants.AccStatic;
		} else {
			this.binding.modifiers |= ClassFileConstants.AccStatic;
		}
		SourceTypeBinding sourceType = currentScope.enclosingSourceType();
		boolean firstSpill = !(this.binding instanceof SyntheticMethodBinding);
		this.binding = sourceType.addSyntheticMethod(this);
		int pc = codeStream.position;
		StringBuffer signature = new StringBuffer();
		signature.append('(');
		if (this.shouldCaptureInstance) {
			codeStream.aload_0();
			signature.append(sourceType.signature());
		}
		for (int i = 0, length = this.outerLocalVariables == null ? 0 : this.outerLocalVariables.length; i < length; i++) {
			SyntheticArgumentBinding syntheticArgument = this.outerLocalVariables[i];
			if (this.shouldCaptureInstance && firstSpill) { // finally block handling results in extra spills, avoid side effect.
				syntheticArgument.resolvedPosition++;
			}
			signature.append(syntheticArgument.type.signature());
			LocalVariableBinding capturedOuterLocal = syntheticArgument.actualOuterLocalVariable;
			VariableBinding[] path = currentScope.getEmulationPath(capturedOuterLocal);
			codeStream.generateOuterAccess(path, this, capturedOuterLocal, currentScope);
		}
		signature.append(')');
		if (this.expectedType instanceof IntersectionTypeBinding18) {
			signature.append(((IntersectionTypeBinding18)this.expectedType).getSAMType(currentScope).signature());
		} else {
			signature.append(this.expectedType.signature());
		}
		int invokeDynamicNumber = codeStream.classFile.recordBootstrapMethod(this);
		codeStream.invokeDynamic(invokeDynamicNumber, (this.shouldCaptureInstance ? 1 : 0) + this.outerLocalVariablesSlotSize, 1, this.descriptor.selector, signature.toString().toCharArray());
		if (!valueRequired)
			codeStream.pop();
		codeStream.recordPositionsFrom(pc, this.sourceStart);		
	}

	public boolean kosherDescriptor(Scope currentScope, MethodBinding sam, boolean shouldChatter) {
		if (sam.typeVariables != Binding.NO_TYPE_VARIABLES) {
			if (shouldChatter)
				currentScope.problemReporter().lambdaExpressionCannotImplementGenericMethod(this, sam);
			return false;
		}
		return super.kosherDescriptor(currentScope, sam, shouldChatter);
	}
	
	/* This code is arranged so that we can continue with as much analysis as possible while avoiding 
	 * mine fields that would result in a slew of spurious messages. This method is a merger of:
	 * @see org.eclipse.jdt.internal.compiler.lookup.MethodScope.createMethod(AbstractMethodDeclaration)
	 * @see org.eclipse.jdt.internal.compiler.lookup.SourceTypeBinding.resolveTypesFor(MethodBinding)
	 * @see org.eclipse.jdt.internal.compiler.ast.AbstractMethodDeclaration.resolve(ClassScope)
	 */
	public TypeBinding resolveType(BlockScope blockScope) {
		
		boolean argumentsTypeElided = argumentsTypeElided();
		int length = this.arguments == null ? 0 : this.arguments.length;
		
		if (this.constant != Constant.NotAConstant) {
			this.constant = Constant.NotAConstant;
			this.enclosingScope = blockScope;
			if (this.original == this)
				this.ordinal = recordFunctionalType(blockScope);
			
			if (!argumentsTypeElided) {
				for (int i = 0; i < length; i++)
					this.argumentTypes[i] = this.arguments[i].type.resolveType(blockScope, true /* check bounds*/);
			}
			if (this.expectedType == null && this.expressionContext == INVOCATION_CONTEXT) {
				return this.resolvedType = new PolyTypeBinding(this);
			} 
		}
		
		MethodScope methodScope = blockScope.methodScope();
		this.scope = new MethodScope(blockScope, this, methodScope.isStatic, methodScope.lastVisibleFieldID);
		this.scope.isConstructorCall = methodScope.isConstructorCall;

		super.resolveType(blockScope); // compute & capture interface function descriptor in singleAbstractMethod.
		
		final boolean haveDescriptor = this.descriptor != null;
		
		if (!haveDescriptor || this.descriptor.typeVariables != Binding.NO_TYPE_VARIABLES) // already complained in kosher*
			return this.resolvedType = null;
		
		this.binding = new MethodBinding(ClassFileConstants.AccPrivate | ClassFileConstants.AccSynthetic | ExtraCompilerModifiers.AccUnresolved,
							CharOperation.concat(TypeConstants.ANONYMOUS_METHOD, Integer.toString(this.ordinal).toCharArray()), // will be fixed up later.
							haveDescriptor ? this.descriptor.returnType : TypeBinding.VOID, 
							Binding.NO_PARAMETERS, // for now. 
							haveDescriptor ? this.descriptor.thrownExceptions : Binding.NO_EXCEPTIONS, 
							blockScope.enclosingSourceType());
		this.binding.typeVariables = Binding.NO_TYPE_VARIABLES;
		
		boolean argumentsHaveErrors = false;
		if (haveDescriptor) {
			int descriptorParameterCount = this.descriptor.parameters.length;
			int lambdaArgumentCount = this.arguments != null ? this.arguments.length : 0;
            if (descriptorParameterCount != lambdaArgumentCount) {
            	this.scope.problemReporter().lambdaSignatureMismatched(this);
            	if (argumentsTypeElided || this.original != this) // no interest in continuing to error check copy.
            		return this.resolvedType = null; // FUBAR, bail out ...
            	else {
            		this.resolvedType = null; // continue to type check.
            		argumentsHaveErrors = true;
            	}
            }
		}
		
		TypeBinding[] newParameters = new TypeBinding[length];

		AnnotationBinding [][] parameterAnnotations = null;
		for (int i = 0; i < length; i++) {
			Argument argument = this.arguments[i];
			if (argument.isVarArgs()) {
				if (i == length - 1) {
					this.binding.modifiers |= ClassFileConstants.AccVarargs;
				} else {
					this.scope.problemReporter().illegalVarargInLambda(argument);
					argumentsHaveErrors = true;
				}
			}
			
			TypeBinding parameterType;
			final TypeBinding expectedParameterType = haveDescriptor && i < this.descriptor.parameters.length ? this.descriptor.parameters[i] : null;
			parameterType = argumentsTypeElided ? expectedParameterType : this.argumentTypes[i];
			if (parameterType == null) {
				argumentsHaveErrors = true;
			} else if (parameterType == TypeBinding.VOID) {
				this.scope.problemReporter().argumentTypeCannotBeVoid(this, argument);
				argumentsHaveErrors = true;
			} else {
				if (!parameterType.isValidBinding()) {
					this.binding.tagBits |= TagBits.HasUnresolvedArguments;
				}
				if ((parameterType.tagBits & TagBits.HasMissingType) != 0) {
					this.binding.tagBits |= TagBits.HasMissingType;
				}
			}
		}
		if (!argumentsTypeElided && !argumentsHaveErrors) {
			ReferenceBinding groundType = null;
			ReferenceBinding expectedSAMType = null;
			if (this.expectedType instanceof IntersectionTypeBinding18)
				expectedSAMType = (ReferenceBinding) ((IntersectionTypeBinding18) this.expectedType).getSAMType(blockScope); 
			else if (this.expectedType instanceof ReferenceBinding)
				expectedSAMType = (ReferenceBinding) this.expectedType;
			if (expectedSAMType != null)
				groundType = findGroundTargetType(blockScope, expectedSAMType, argumentsTypeElided);
			
			if (groundType != null) {
				this.descriptor = groundType.getSingleAbstractMethod(blockScope, true);
				if (!this.descriptor.isValidBinding()) {
					reportSamProblem(blockScope, this.descriptor);
				} else {
					if (groundType != expectedSAMType) { //$IDENTITY-COMPARISON$
						if (!groundType.isCompatibleWith(expectedSAMType, this.scope)) { // the ground has shifted, are we still on firm grounds ? 
							blockScope.problemReporter().typeMismatchError(groundType, this.expectedType, this, null); // report deliberately against block scope so as not to blame the lambda.
							return this.resolvedType = null;
						}
					}
					this.resolvedType = groundType;
				}
			}
		}
		for (int i = 0; i < length; i++) {
			Argument argument = this.arguments[i];
			TypeBinding parameterType;
			final TypeBinding expectedParameterType = haveDescriptor && i < this.descriptor.parameters.length ? this.descriptor.parameters[i] : null;
			parameterType = argumentsTypeElided ? expectedParameterType : this.argumentTypes[i];
			if (parameterType != null && parameterType != TypeBinding.VOID) {
				if (haveDescriptor && expectedParameterType != null && parameterType.isValidBinding() && TypeBinding.notEquals(parameterType, expectedParameterType)) {
					if (expectedParameterType.isProperType(true)) {
						this.scope.problemReporter().lambdaParameterTypeMismatched(argument, argument.type, expectedParameterType);
						this.resolvedType = null; // continue to type check.
					}
				}
				if (this.requiresGenericSignature) {
					TypeBinding leafType = parameterType.leafComponentType();
					if (leafType instanceof ReferenceBinding && (((ReferenceBinding) leafType).modifiers & ExtraCompilerModifiers.AccGenericSignature) != 0)
						this.binding.modifiers |= ExtraCompilerModifiers.AccGenericSignature;
				}
				newParameters[i] = argument.bind(this.scope, parameterType, false);				
				if (argument.annotations != null) {
					this.binding.tagBits |= TagBits.HasParameterAnnotations;
					if (parameterAnnotations == null) {
						parameterAnnotations = new AnnotationBinding[length][];
						for (int j = 0; j < i; j++) {
							parameterAnnotations[j] = Binding.NO_ANNOTATIONS;
						}
					}
					parameterAnnotations[i] = argument.binding.getAnnotations();
				} else if (parameterAnnotations != null) {
					parameterAnnotations[i] = Binding.NO_ANNOTATIONS;
				}
			}
		}
		// only assign parameters if no problems are found
		if (!argumentsHaveErrors) {
			this.binding.parameters = newParameters;
			if (parameterAnnotations != null)
				this.binding.setParameterAnnotations(parameterAnnotations);
		}
	
		if (!argumentsTypeElided && this.binding.isVarargs()) {
			if (!this.binding.parameters[this.binding.parameters.length - 1].isReifiable()) {
				this.scope.problemReporter().possibleHeapPollutionFromVararg(this.arguments[this.arguments.length - 1]);
			}
		}

		ReferenceBinding [] exceptions = this.binding.thrownExceptions;
		length = exceptions.length;
		for (int i = 0; i < length; i++) {
			ReferenceBinding exception = exceptions[i];
			if ((exception.tagBits & TagBits.HasMissingType) != 0) {
				this.binding.tagBits |= TagBits.HasMissingType;
			}
			if (this.requiresGenericSignature)
				this.binding.modifiers |= (exception.modifiers & ExtraCompilerModifiers.AccGenericSignature);
		}
		
		TypeBinding returnType = this.binding.returnType;
		if (returnType != null) {
			if ((returnType.tagBits & TagBits.HasMissingType) != 0) {
				this.binding.tagBits |= TagBits.HasMissingType;
			}
			if (this.requiresGenericSignature) {
				TypeBinding leafType = returnType.leafComponentType();
				if (leafType instanceof ReferenceBinding && (((ReferenceBinding) leafType).modifiers & ExtraCompilerModifiers.AccGenericSignature) != 0)
					this.binding.modifiers |= ExtraCompilerModifiers.AccGenericSignature;
			}
		} // TODO (stephan): else? (can that happen?)

		if (haveDescriptor && !argumentsHaveErrors && blockScope.compilerOptions().isAnnotationBasedNullAnalysisEnabled) {
			if (!argumentsTypeElided) {
				AbstractMethodDeclaration.createArgumentBindings(this.arguments, this.binding, this.scope);
				validateNullAnnotations();
				// no application of null-ness default, hence also no warning regarding redundant null annotation
				mergeParameterNullAnnotations(blockScope);
			}
			this.binding.tagBits |= (this.descriptor.tagBits & TagBits.AnnotationNullMASK);
		}

		this.binding.modifiers &= ~ExtraCompilerModifiers.AccUnresolved;
		
		if (this.body instanceof Expression) {
			Expression expression = (Expression) this.body;
			new ReturnStatement(expression, expression.sourceStart, expression.sourceEnd, true).resolve(this.scope); // :-) ;-)
		} else {
			this.body.resolve(this.scope);
		}
		if (this.expectedType instanceof IntersectionTypeBinding18) {
			ReferenceBinding[] intersectingTypes =  ((IntersectionTypeBinding18)this.expectedType).intersectingTypes;
			for (int t = 0, max = intersectingTypes.length; t < max; t++) {
				if (intersectingTypes[t].findSuperTypeOriginatingFrom(TypeIds.T_JavaIoSerializable, false /*Serializable is not a class*/) != null) {
					this.isSerializable = true;
					break;
				}
			}
		} else if (this.expectedType != null && 
				   this.expectedType.findSuperTypeOriginatingFrom(TypeIds.T_JavaIoSerializable, false /*Serializable is not a class*/) != null) {
			this.isSerializable = true;
		}
		if ((this.binding.tagBits & TagBits.HasMissingType) != 0) {
			this.scope.problemReporter().missingTypeInLambda(this, this.binding);
		}
		if (this.shouldCaptureInstance && this.scope.isConstructorCall) {
			this.scope.problemReporter().fieldsOrThisBeforeConstructorInvocation(this);
		}
		return this.resolvedType;
	}

	private ReferenceBinding findGroundTargetType(BlockScope blockScope, TypeBinding targetType, boolean argumentTypesElided) {
		
		if (targetType instanceof IntersectionTypeBinding18)
			targetType = ((IntersectionTypeBinding18) targetType).getSAMType(blockScope); 
		
		if (targetType instanceof ReferenceBinding && targetType.isValidBinding()) {
			ParameterizedTypeBinding withWildCards = InferenceContext18.parameterizedWithWildcard(targetType);
			if (withWildCards != null) {
				if (!argumentTypesElided)
					return new InferenceContext18(blockScope).inferFunctionalInterfaceParameterization(this, blockScope, withWildCards);
				else
					return findGroundTargetTypeForElidedLambda(blockScope, withWildCards);
			}
			return (ReferenceBinding) targetType;
		}
		return null;
	}

	public ReferenceBinding findGroundTargetTypeForElidedLambda(BlockScope blockScope, ParameterizedTypeBinding withWildCards) {
		// non-wildcard parameterization (9.8) of the target type
		TypeBinding[] types = withWildCards.getNonWildcardParameterization(blockScope);
		if (types == null)
			return null;
		ReferenceBinding genericType = withWildCards.genericType();
		return blockScope.environment().createParameterizedType(genericType, types, withWildCards.enclosingType());
	}

	public boolean argumentsTypeElided() {
		return this.arguments.length > 0 && this.arguments[0].hasElidedType();
	}

	private boolean doesNotCompleteNormally() {
		try {
			return this.body.analyseCode(this.scope, 
									 new ExceptionHandlingFlowContext(null, this, Binding.NO_EXCEPTIONS, null, this.scope, FlowInfo.DEAD_END), 
									 UnconditionalFlowInfo.fakeInitializedFlowInfo(this.scope.outerMostMethodScope().analysisIndex, this.scope.referenceType().maxFieldCount)) == FlowInfo.DEAD_END;
		} catch (RuntimeException e) {
			/* See https://bugs.eclipse.org/bugs/show_bug.cgi?id=432110 for an example of where the flow analysis can result in run time error.
			   We can recover and do the right thing by falling back on the results of the structural analysis done already and be right 99.99%
			   of the time. Strictly speaking void/value compatibility is not a structural property. { throw NPE(); } is value compatible despite
			   structurally there not being a return statement. Likewise { if (x) return value; } is not value compatible despite there being a
			   return statement. We will miss the former case, but that is mostly pedantic. We would misclassify the latter case *here*, but it
			   would be caught elsewhere, so it should all wash out in the end. 
			*/ 
			return this.valueCompatible;
		}
	}
	public FlowInfo analyseCode(BlockScope currentScope, FlowContext flowContext, final FlowInfo flowInfo) {
		
		if (this.ignoreFurtherInvestigation) 
			return flowInfo;
		
		FlowInfo lambdaInfo = flowInfo.copy(); // what happens in vegas, stays in vegas ...
		ExceptionHandlingFlowContext methodContext =
				new ExceptionHandlingFlowContext(
						flowContext,
						this,
						this.binding.thrownExceptions,
						flowContext.getInitializationContext(),
						this.scope,
						FlowInfo.DEAD_END);

		// nullity and mark as assigned
		MethodBinding methodWithParameterDeclaration = argumentsTypeElided() ? this.descriptor : this.binding;
		AbstractMethodDeclaration.analyseArguments18(lambdaInfo, this.arguments, methodWithParameterDeclaration);

		if (this.arguments != null) {
			for (int i = 0, count = this.arguments.length; i < count; i++) {
				this.bits |= (this.arguments[i].bits & ASTNode.HasTypeAnnotations);
			}
		}
		
		lambdaInfo = this.body.analyseCode(this.scope, methodContext, lambdaInfo);
		
		// check for missing returning path for block body's ...
		if (this.body instanceof Block) {
			TypeBinding returnTypeBinding = expectedResultType();
			if ((returnTypeBinding == TypeBinding.VOID)) {
				if ((lambdaInfo.tagBits & FlowInfo.UNREACHABLE_OR_DEAD) == 0 || ((Block) this.body).statements == null) {
					this.bits |= ASTNode.NeedFreeReturn;
				}
			} else {
				if (lambdaInfo != FlowInfo.DEAD_END) {
					this.scope.problemReporter().shouldReturn(returnTypeBinding, this);
				}
			}
		} else { // Expression
			if (currentScope.compilerOptions().isAnnotationBasedNullAnalysisEnabled 
					&& lambdaInfo.reachMode() == FlowInfo.REACHABLE)
			{
				Expression expression = (Expression)this.body;
				checkAgainstNullAnnotation(flowContext, expression, expression.nullStatus(lambdaInfo, flowContext));
			}
		}
		return flowInfo;
	}

	// cf. AbstractMethodDeclaration.validateNullAnnotations()
	// pre: !argumentTypeElided()
	void validateNullAnnotations() {
		// null annotations on parameters?
		if (this.binding != null) {
			int length = this.binding.parameters.length;
			for (int i=0; i<length; i++) {
				if (!this.scope.validateNullAnnotation(this.binding.returnType.tagBits, this.arguments[i].type, this.arguments[i].annotations))
					this.binding.returnType = this.binding.returnType.withoutToplevelNullAnnotation();
			}
		}
	}

	// pre: !argumentTypeElided()
	// try to merge null annotations from descriptor into binding, complaining about any incompatibilities found
	private void mergeParameterNullAnnotations(BlockScope currentScope) {
		LookupEnvironment env = currentScope.environment();
		TypeBinding[] ourParameters = this.binding.parameters;
		TypeBinding[] descParameters = this.descriptor.parameters;
		int len = Math.min(ourParameters.length, descParameters.length);
		for (int i = 0; i < len; i++) {
			long ourTagBits = ourParameters[i].tagBits & TagBits.AnnotationNullMASK;
			long descTagBits = descParameters[i].tagBits & TagBits.AnnotationNullMASK;
			if (ourTagBits == 0L) {
				if (descTagBits != 0L && !ourParameters[i].isBaseType()) {
					AnnotationBinding [] annotations = descParameters[i].getTypeAnnotations();
					for (int j = 0, length = annotations.length; j < length; j++) {
						AnnotationBinding annotation = annotations[j];
						if (annotation != null) {
							switch (annotation.getAnnotationType().id) {
								case TypeIds.T_ConfiguredAnnotationNullable :
								case TypeIds.T_ConfiguredAnnotationNonNull :
									ourParameters[i] = env.createAnnotatedType(ourParameters[i], new AnnotationBinding [] { annotation });
									break;
							}
						}
					}
				}
			} else if (ourTagBits != descTagBits) {
				if (ourTagBits == TagBits.AnnotationNonNull) { // requested @NonNull not provided
					char[][] inheritedAnnotationName = null;
					if (descTagBits == TagBits.AnnotationNullable)
						inheritedAnnotationName = env.getNullableAnnotationName();
					currentScope.problemReporter().illegalRedefinitionToNonNullParameter(this.arguments[i], this.descriptor.declaringClass, inheritedAnnotationName);
				}
			}			
		}
	}

	// simplified version of ReturnStatement.checkAgainstNullAnnotation()
	void checkAgainstNullAnnotation(FlowContext flowContext, Expression expression, int nullStatus) {
		if (nullStatus != FlowInfo.NON_NULL) {
			// if we can't prove non-null check against declared null-ness of the descriptor method:
			// Note that this.binding never has a return type declaration, always inherit null-ness from the descriptor
			if ((this.descriptor.returnType.tagBits & TagBits.AnnotationNonNull) != 0) {
				flowContext.recordNullityMismatch(this.scope, expression, expression.resolvedType, this.descriptor.returnType, nullStatus);
			}
		}
	}

	public boolean isPertinentToApplicability(TypeBinding targetType, MethodBinding method) {
		if (targetType == null) // assumed to signal another primary error
			return true;
		
		if (argumentsTypeElided())
			return false;
		
		if (!super.isPertinentToApplicability(targetType, method))
			return false;
		
		if (this.body instanceof Expression) {
			if (!((Expression) this.body).isPertinentToApplicability(targetType, method))
				return false;
		} else {
			Expression [] returnExpressions = this.resultExpressions;
			for (int i = 0, length = returnExpressions.length; i < length; i++) {
				if (!returnExpressions[i].isPertinentToApplicability(targetType, method))
					return false;
			}
		}
		
		return true;
	}
	
	public boolean isVoidCompatible() {
		return this.voidCompatible;
	}

	public boolean isValueCompatible() {
		return this.valueCompatible;
	}
	
	public StringBuffer printExpression(int tab, StringBuffer output) {
		return printExpression(tab, output, false);
	}

	public StringBuffer printExpression(int tab, StringBuffer output, boolean makeShort) {
		int parenthesesCount = (this.bits & ASTNode.ParenthesizedMASK) >> ASTNode.ParenthesizedSHIFT;
		String suffix = ""; //$NON-NLS-1$
		for(int i = 0; i < parenthesesCount; i++) {
			output.append('(');
			suffix += ')';
		}
		output.append('(');
		if (this.arguments != null) {
			for (int i = 0; i < this.arguments.length; i++) {
				if (i > 0) output.append(", "); //$NON-NLS-1$
				this.arguments[i].print(0, output);
			}
		}
		output.append(") -> " ); //$NON-NLS-1$
		if (makeShort) {
			output.append("{}"); //$NON-NLS-1$
		} else {
			if (this.body != null)
				this.body.print(this.body instanceof Block ? tab : 0, output);
			else
				output.append("<@incubator>"); //$NON-NLS-1$
		}
		return output.append(suffix);
	}

	public TypeBinding expectedResultType() {
		return this.descriptor != null && this.descriptor.isValidBinding() ? this.descriptor.returnType : null;
	}
	
	public void traverse(ASTVisitor visitor, BlockScope blockScope) {

			if (visitor.visit(this, blockScope)) {
				if (this.arguments != null) {
					int argumentsLength = this.arguments.length;
					for (int i = 0; i < argumentsLength; i++)
						this.arguments[i].traverse(visitor, this.scope);
				}

				if (this.body != null) {
					this.body.traverse(visitor, this.scope);
				}
			}
			visitor.endVisit(this, blockScope);
	}
	
	public MethodScope getScope() {
		return this.scope;
	}
	
	private boolean enclosingScopesHaveErrors() {
		Scope skope = this.enclosingScope;
		while (skope != null) {
			ReferenceContext context = skope.referenceContext();
			if (context != null && context.hasErrors())
				return true;
			skope = skope.parent;
		}
		return false;
	}
		
	private void analyzeShape() { // Simple minded analysis for code assist & potential compatibility.
		class ShapeComputer extends ASTVisitor {
			public boolean visit(TypeDeclaration type, BlockScope skope) {
				return false;
			}
			public boolean visit(TypeDeclaration type, ClassScope skope) {
				return false;
			}
			public boolean visit(LambdaExpression type, BlockScope skope) {
				return false;
			}
		    public boolean visit(ReturnStatement returnStatement, BlockScope skope) {
		    	if (returnStatement.expression != null) {
		    		LambdaExpression.this.valueCompatible = true;
		    		LambdaExpression.this.voidCompatible = false;
		    	} else {
		    		LambdaExpression.this.voidCompatible = true;
		    		LambdaExpression.this.valueCompatible = false;
		    	}
		    	return false;
		    }
		}
		if (this.body instanceof Expression) {
			// When completion is still in progress, it is not possible to ask if the expression constitutes a statement expression. See https://bugs.eclipse.org/bugs/show_bug.cgi?id=435219
			this.voidCompatible = this.assistNode ? true : ((Expression) this.body).statementExpression();
			this.valueCompatible = true;
		} else {
			// For code assist, we need to be a bit tolerant/fuzzy here: the code is being written "just now", if we are too pedantic, selection/completion will break;
			if (this.assistNode) {
				this.voidCompatible = true;
				this.valueCompatible = true;
			}
			this.body.traverse(new ShapeComputer(), null);
			Block block = (Block) this.body;
			// support the idiom that { throw new Exception(); } is value compatible.
			if (block.statements != null && block.statements.length == 1 && block.statements[0] instanceof ThrowStatement)
				this.valueCompatible = true;
		}
	}
	
	@Override
	public boolean isPotentiallyCompatibleWith(TypeBinding targetType, Scope skope) {
		/* We get here only when the lambda is NOT pertinent to applicability and that too only for type elided lambdas. */
		
		/* 15.12.2.1: A lambda expression (§15.27) is potentially compatible with a functional interface type (§9.8) if all of the following are true:
		       – The arity of the target type's function type is the same as the arity of the lambda expression.
		       – If the target type's function type has a void return, then the lambda body is either a statement expression (§14.8) or a void-compatible block (§15.27.2).
		       – If the target type's function type has a (non-void) return type, then the lambda body is either an expression or a value-compatible block (§15.27.2).
		*/
		if (!super.isPertinentToApplicability(targetType, null))
			return true;
		
		final MethodBinding sam = targetType.getSingleAbstractMethod(skope, true);
		if (sam == null || !sam.isValidBinding())
			return false;
		
		if (sam.parameters.length != this.arguments.length)
			return false;
		
		analyzeShape();
		if (sam.returnType.id == TypeIds.T_void) {
			if (!this.voidCompatible)
				return false;
		} else {
			if (!this.valueCompatible)
				return false;
		}
		return true;
	}
	
	public boolean isCompatibleWith(TypeBinding targetType, final Scope skope) {
		
		if (!super.isPertinentToApplicability(targetType, null))
			return true;
		
		LambdaExpression copy = null;
		try {
			copy = cachedResolvedCopy(targetType, argumentsTypeElided()); // if argument types are elided, we don't care for result expressions against *this* target, any port in a storm is ok.
		} catch (CopyFailureException cfe) {
			if (this.assistNode)
				return true; // can't type check result expressions, just say yes.
			return !isPertinentToApplicability(targetType, null); // don't expect to hit this ever.
		}
		if (copy == null)
			return false;
		
		IErrorHandlingPolicy oldPolicy = this.enclosingScope.problemReporter().switchErrorHandlingPolicy(silentErrorHandlingPolicy);
		final CompilerOptions compilerOptions = this.enclosingScope.compilerOptions();
		boolean analyzeNPE = compilerOptions.isAnnotationBasedNullAnalysisEnabled;
		compilerOptions.isAnnotationBasedNullAnalysisEnabled = false;
		try {
			/* At this point, shape analysis is complete for ((see returnsExpression(...))
			       - a lambda with an expression body,
				   - a lambda with a block body in which we saw a return statement naked or otherwise.
			*/
			if (copy.body instanceof Block && !copy.returnsVoid && !copy.returnsValue && !copy.valueCompatible) {
				// Do not proceed with data/control flow analysis if resolve encountered errors.
				if (copy.hasIgnoredMandatoryErrors || enclosingScopesHaveErrors()) {
					if (isPertinentToApplicability(targetType, null))
						if (copy.arguments.length != 0) // ?? Needs check. 
							return false;
				} else {
					copy.valueCompatible = copy.doesNotCompleteNormally();
				}
			}
		} finally {
			compilerOptions.isAnnotationBasedNullAnalysisEnabled = analyzeNPE;
			this.enclosingScope.problemReporter().switchErrorHandlingPolicy(oldPolicy);
		}

		targetType = findGroundTargetType(this.enclosingScope, targetType, argumentsTypeElided());
		MethodBinding sam = targetType.getSingleAbstractMethod(this.enclosingScope, true);
		if (sam.returnType.id == TypeIds.T_void) {
			if (!copy.voidCompatible)
				return false;
		} else {
			if (!copy.valueCompatible)
				return false;
		}

		if (!isPertinentToApplicability(targetType, null))
			return true;

		Expression [] returnExpressions = copy.resultExpressions;
		for (int i = 0, length = returnExpressions.length; i < length; i++) {
			if (this.enclosingScope.parameterCompatibilityLevel(returnExpressions[i].resolvedType, sam.returnType) == Scope.NOT_COMPATIBLE) {
				if (!returnExpressions[i].isConstantValueOfTypeAssignableToType(returnExpressions[i].resolvedType, sam.returnType))
					if (sam.returnType.id != TypeIds.T_void || this.body instanceof Block)
						return false;
			}
		}
		return true;
	}
	
	class CopyFailureException extends RuntimeException {
		private static final long serialVersionUID = 1L;
	}

	private LambdaExpression cachedResolvedCopy(TypeBinding targetType, boolean anyTargetOk) {

		targetType = findGroundTargetType(this.enclosingScope, targetType, argumentsTypeElided());
		if (targetType == null)
			return null;
		
		MethodBinding sam = targetType.getSingleAbstractMethod(this.enclosingScope, true);
		if (sam == null || !sam.isValidBinding())
			return null;
		
		if (sam.parameters.length != this.arguments.length)
			return null;
		
		LambdaExpression copy = null;
		if (this.copiesPerTargetType != null) {
			copy = this.copiesPerTargetType.get(targetType);
			if (copy != null)
				return copy;
			if (anyTargetOk && this.copiesPerTargetType.values().size() > 0)
				return this.copiesPerTargetType.values().iterator().next();
		}
		
		IErrorHandlingPolicy oldPolicy = this.enclosingScope.problemReporter().switchErrorHandlingPolicy(silentErrorHandlingPolicy);
		try {
			copy = copy();
			if (copy == null)
				throw new CopyFailureException();
			
			copy.setExpressionContext(this.expressionContext);
			copy.setExpectedType(targetType);
			TypeBinding type = copy.resolveType(this.enclosingScope);
			if (type == null || !type.isValidBinding())
				return null;
	
			if (this.copiesPerTargetType == null)
				this.copiesPerTargetType = new HashMap<TypeBinding, LambdaExpression>();
			this.copiesPerTargetType.put(targetType, copy);
			
			return copy;
		} finally {
			this.enclosingScope.problemReporter().switchErrorHandlingPolicy(oldPolicy);
		}
	}
	
	/**
	 * Get a resolved copy of this lambda for use by type inference, as to avoid spilling any premature
	 * type results into the original lambda.
	 * 
	 * @param targetType the target functional type against which inference is attempted, must be a non-null valid functional type 
	 * @return a resolved copy of 'this' or null if significant errors where encountered
	 */
	public LambdaExpression resolveExpressionExpecting(TypeBinding targetType, Scope skope) {
		LambdaExpression copy = null;
		try {
			copy = cachedResolvedCopy(targetType, false);
		} catch (CopyFailureException cfe) {
			return null;
		}
		if (copy == null) {
			return null;
		}
		final CompilerOptions compilerOptions = this.enclosingScope.compilerOptions();
		boolean analyzeNPE = compilerOptions.isAnnotationBasedNullAnalysisEnabled;
		IErrorHandlingPolicy oldPolicy = this.enclosingScope.problemReporter().switchErrorHandlingPolicy(silentErrorHandlingPolicy);
		try {
			compilerOptions.isAnnotationBasedNullAnalysisEnabled = false;
			// Do not proceed with data/control flow analysis if resolve encountered errors.
			if (!copy.hasIgnoredMandatoryErrors && !enclosingScopesHaveErrors()) {
				// value compatibility of block lambda's is the only open question.
				copy.valueCompatible |= copy.doesNotCompleteNormally();
			} else {
				if (!copy.returnsVoid)
					copy.valueCompatible = true; // optimistically, TODO: is this OK??
			}
		} finally {
			compilerOptions.isAnnotationBasedNullAnalysisEnabled = analyzeNPE;
			this.enclosingScope.problemReporter().switchErrorHandlingPolicy(oldPolicy);
		}
		return copy;
	}

	public boolean sIsMoreSpecific(TypeBinding s, TypeBinding t, Scope skope) {
		
		// 15.12.2.5 
		
		if (super.sIsMoreSpecific(s, t, skope))
			return true;
		
		if (argumentsTypeElided() || t.findSuperTypeOriginatingFrom(s) != null)
			return false;
		
		s = s.capture(this.enclosingScope, this.sourceStart, this.sourceEnd);
		MethodBinding sSam = s.getSingleAbstractMethod(this.enclosingScope, true);
		if (sSam == null || !sSam.isValidBinding())
			return false;
		TypeBinding r1 = sSam.returnType;
		MethodBinding tSam = t.getSingleAbstractMethod(this.enclosingScope, true);
		if (tSam == null || !tSam.isValidBinding())
			return true; // See ORT8.test450415a for a case that slips through isCompatibleWith.
		TypeBinding r2 = tSam.returnType;
		
		if (r2.id == TypeIds.T_void)
			return true;
		
		if (r1.id == TypeIds.T_void)
			return false;
		
		// r1 <: r2
		if (r1.isCompatibleWith(r2, skope))
			return true;
		
		LambdaExpression copy = cachedResolvedCopy(s, true /* any resolved copy is good */);
		Expression [] returnExpressions = copy.resultExpressions;
		int returnExpressionsLength = returnExpressions == null ? 0 : returnExpressions.length;
		
		int i;
		// r1 is a primitive type, r2 is a reference type, and each result expression is a standalone expression (15.2) of a primitive type
		if (r1.isBaseType() && !r2.isBaseType()) {
			for (i = 0; i < returnExpressionsLength; i++) {
				if (returnExpressions[i].isPolyExpression() || !returnExpressions[i].resolvedType.isBaseType())
					break;
			}
			if (i == returnExpressionsLength)
				return true;
		}
		if (!r1.isBaseType() && r2.isBaseType()) {
			for (i = 0; i < returnExpressionsLength; i++) {
				if (returnExpressions[i].resolvedType.isBaseType())
					break;
			}
			if (i == returnExpressionsLength)
				return true;
		}
		if (r1.isFunctionalInterface(this.enclosingScope) && r2.isFunctionalInterface(this.enclosingScope)) {
			for (i = 0; i < returnExpressionsLength; i++) {
				Expression resultExpression = returnExpressions[i];
				if (!resultExpression.sIsMoreSpecific(r1, r2, skope))
					break;
			}
			if (i == returnExpressionsLength)
				return true;
		}
		return false;
	}

	LambdaExpression copy() {
		final Parser parser = new Parser(this.enclosingScope.problemReporter(), false);
		final ICompilationUnit compilationUnit = this.compilationResult.getCompilationUnit();
		char[] source = compilationUnit != null ? compilationUnit.getContents() : this.text;
		LambdaExpression copy =  (LambdaExpression) parser.parseLambdaExpression(source, compilationUnit != null ? this.sourceStart : 0, this.sourceEnd - this.sourceStart + 1, 
										this.enclosingScope.referenceCompilationUnit(), false /* record line separators */);

		if (copy != null) { // ==> syntax errors == null
			copy.original = this;
			copy.assistNode = this.assistNode;
			copy.enclosingScope = this.enclosingScope;
		}
		return copy;
	}

	public void returnsExpression(Expression expression, TypeBinding resultType) {
		if (this.original == this) // Not in overload resolution context. result expressions not relevant.
			return;
		if (this.body instanceof Expression) {
			this.valueCompatible = resultType != null && resultType.id == TypeIds.T_void ? false : true;
			this.voidCompatible = this.assistNode ? true : ((Expression) this.body).statementExpression(); // while code is still being written and completed, we can't ask if it is a statement
			this.resultExpressions = new Expression[] { expression };
			return;
		}
		if (expression != null) {
			this.returnsValue = true;
			this.voidCompatible = false;
			this.valueCompatible = !this.returnsVoid;
			if (resultType != null) {
				Expression [] returnExpressions = this.resultExpressions;
				int resultsLength = returnExpressions.length;
				System.arraycopy(returnExpressions, 0, returnExpressions = new Expression[resultsLength + 1], 0, resultsLength);
				returnExpressions[resultsLength] = expression;
				this.resultExpressions = returnExpressions;
			}
		} else {
			this.returnsVoid = true;
			this.valueCompatible = false;
			this.voidCompatible = !this.returnsValue;
		}
	}
	
	public CompilationResult compilationResult() {
		return this.compilationResult;
	}

	public void abort(int abortLevel, CategorizedProblem problem) {
	
		switch (abortLevel) {
			case AbortCompilation :
				throw new AbortCompilation(this.compilationResult, problem);
			case AbortCompilationUnit :
				throw new AbortCompilationUnit(this.compilationResult, problem);
			case AbortType :
				throw new AbortType(this.compilationResult, problem);
			default :
				throw new AbortMethod(this.compilationResult, problem);
		}
	}

	public CompilationUnitDeclaration getCompilationUnitDeclaration() {
		return this.enclosingScope == null ? null : this.enclosingScope.compilationUnitScope().referenceContext;
	}

	public boolean hasErrors() {
		return this.ignoreFurtherInvestigation;
	}

	public void tagAsHavingErrors() {
		this.ignoreFurtherInvestigation = true;
		Scope parent = this.enclosingScope.parent;
		while (parent != null) {
			switch(parent.kind) {
				case Scope.CLASS_SCOPE:
				case Scope.METHOD_SCOPE:
					parent.referenceContext().tagAsHavingErrors();
					return;
				default:
					parent = parent.parent;
					break;
			}
		}
	}
	
	public void tagAsHavingIgnoredMandatoryErrors(int problemId) {
		switch (problemId) {
			// 15.27.3 requires exception throw related errors to not influence congruence. Other errors should. Also don't abort shape analysis.
			case IProblem.UnhandledExceptionOnAutoClose:
			case IProblem.UnhandledExceptionInDefaultConstructor:
			case IProblem.UnhandledException:
				return;
			/* The following structural problems can occur only because of target type imposition. Filter, so we can distinguish inherent errors 
			   in explicit lambdas. This is to help decide whether to proceed with data/control flow analysis to discover shape. In case of inherent
			   errors, we will not call analyze code as it is not prepared to analyze broken programs.
			*/
			case IProblem.VoidMethodReturnsValue:
			case IProblem.ShouldReturnValueHintMissingDefault:
			case IProblem.ShouldReturnValue:
			case IProblem.ReturnTypeMismatch:
			case IProblem.IncompatibleLambdaParameterType:
			case IProblem.lambdaParameterTypeMismatched:
			case IProblem.lambdaSignatureMismatched:
			case IProblem.LambdaDescriptorMentionsUnmentionable:
			case IProblem.TargetTypeNotAFunctionalInterface:
			case IProblem.illFormedParameterizationOfFunctionalInterface:
			case IProblem.MultipleFunctionalInterfaces:
			case IProblem.NoGenericLambda:
				return;
			default: 
				this.hasIgnoredMandatoryErrors = true;
				MethodScope enclosingLambdaScope = this.scope == null ? null : this.scope.enclosingLambdaScope();
				while (enclosingLambdaScope != null) {
					LambdaExpression enclosingLambda = (LambdaExpression) enclosingLambdaScope.referenceContext;
					enclosingLambda.hasIgnoredMandatoryErrors = true;
					enclosingLambdaScope = enclosingLambdaScope.enclosingLambdaScope();
				}
				return;
		}
	}
	
	public void throwsException(TypeBinding exceptionType) {
		if (this.expressionContext != INVOCATION_CONTEXT)
			return;
		if (this.thrownExceptions == null)
			this.thrownExceptions = new HashSet<TypeBinding>();
		this.thrownExceptions.add(exceptionType);
	}

	public Set<TypeBinding> getThrownExceptions() {
		if (this.thrownExceptions == null)
			return Collections.emptySet();
		return this.thrownExceptions;
	}

	public void generateCode(ClassScope classScope, ClassFile classFile) {
		int problemResetPC = 0;
		classFile.codeStream.wideMode = false;
		boolean restart = false;
		do {
			try {
				problemResetPC = classFile.contentsOffset;
				this.generateCode(classFile);
				restart = false;
			} catch (AbortMethod e) {
				// Restart code generation if possible ...
				if (e.compilationResult == CodeStream.RESTART_IN_WIDE_MODE) {
					// a branch target required a goto_w, restart code generation in wide mode.
					classFile.contentsOffset = problemResetPC;
					classFile.methodCount--;
					classFile.codeStream.resetInWideMode(); // request wide mode
					restart = true;
				} else if (e.compilationResult == CodeStream.RESTART_CODE_GEN_FOR_UNUSED_LOCALS_MODE) {
					classFile.contentsOffset = problemResetPC;
					classFile.methodCount--;
					classFile.codeStream.resetForCodeGenUnusedLocals();
					restart = true;
				} else {
					throw new AbortType(this.compilationResult, e.problem);
				}
			}
		} while (restart);
	}
	
	public void generateCode(ClassFile classFile) {
		classFile.generateMethodInfoHeader(this.binding);
		int methodAttributeOffset = classFile.contentsOffset;
		int attributeNumber = classFile.generateMethodInfoAttributes(this.binding);
		int codeAttributeOffset = classFile.contentsOffset;
		classFile.generateCodeAttributeHeader();
		CodeStream codeStream = classFile.codeStream;
		codeStream.reset(this, classFile);
		// initialize local positions
		this.scope.computeLocalVariablePositions(this.outerLocalVariablesSlotSize + (this.binding.isStatic() ? 0 : 1), codeStream);
		if (this.outerLocalVariables != null) {
			for (int i = 0, max = this.outerLocalVariables.length; i < max; i++) {
				LocalVariableBinding argBinding;
				codeStream.addVisibleLocalVariable(argBinding = this.outerLocalVariables[i]);
				codeStream.record(argBinding);
				argBinding.recordInitializationStartPC(0);
			}
		}
		// arguments initialization for local variable debug attributes
		if (this.arguments != null) {
			for (int i = 0, max = this.arguments.length; i < max; i++) {
				LocalVariableBinding argBinding;
				codeStream.addVisibleLocalVariable(argBinding = this.arguments[i].binding);
				argBinding.recordInitializationStartPC(0);
			}
		}
		if (this.body instanceof Block) {
			this.body.generateCode(this.scope, codeStream);
			if ((this.bits & ASTNode.NeedFreeReturn) != 0) {
				codeStream.return_();
			}
		} else {
			Expression expression = (Expression) this.body;
			expression.generateCode(this.scope, codeStream, true);
			if (this.binding.returnType == TypeBinding.VOID) {
				codeStream.return_();
			} else {
				codeStream.generateReturnBytecode(expression);
			}
		}
		// local variable attributes
		codeStream.exitUserScope(this.scope);
		codeStream.recordPositionsFrom(0, this.sourceEnd); // WAS declarationSourceEnd.
		try {
			classFile.completeCodeAttribute(codeAttributeOffset);
		} catch(NegativeArraySizeException e) {
			throw new AbortMethod(this.scope.referenceCompilationUnit().compilationResult, null);
		}
		attributeNumber++;

		classFile.completeMethodInfo(this.binding, methodAttributeOffset, attributeNumber);
	}
	
	public void addSyntheticArgument(LocalVariableBinding actualOuterLocalVariable) {
		
		if (this.original != this || this.binding == null) 
			return; // Do not bother tracking outer locals for clones created during overload resolution.
		
		SyntheticArgumentBinding syntheticLocal = null;
		int newSlot = this.outerLocalVariables.length;
		for (int i = 0; i < newSlot; i++) {
			if (this.outerLocalVariables[i].actualOuterLocalVariable == actualOuterLocalVariable)
				return;
		}
		System.arraycopy(this.outerLocalVariables, 0, this.outerLocalVariables = new SyntheticArgumentBinding[newSlot + 1], 0, newSlot);
		this.outerLocalVariables[newSlot] = syntheticLocal = new SyntheticArgumentBinding(actualOuterLocalVariable);
		syntheticLocal.resolvedPosition = this.outerLocalVariablesSlotSize; // may need adjusting later if we need to generate an instance method for the lambda.
		syntheticLocal.declaringScope = this.scope;
		int parameterCount = this.binding.parameters.length;
		TypeBinding [] newParameters = new TypeBinding[parameterCount + 1];
		newParameters[newSlot] = actualOuterLocalVariable.type;
		for (int i = 0, j = 0; i < parameterCount; i++, j++) {
			if (i == newSlot) j++;
			newParameters[j] = this.binding.parameters[i];
		}
		this.binding.parameters = newParameters;
		switch (syntheticLocal.type.id) {
			case TypeIds.T_long :
			case TypeIds.T_double :
				this.outerLocalVariablesSlotSize  += 2;
				break;
			default :
				this.outerLocalVariablesSlotSize++;
				break;
		}		
	}

	public SyntheticArgumentBinding getSyntheticArgument(LocalVariableBinding actualOuterLocalVariable) {
		for (int i = 0, length = this.outerLocalVariables == null ? 0 : this.outerLocalVariables.length; i < length; i++)
			if (this.outerLocalVariables[i].actualOuterLocalVariable == actualOuterLocalVariable)
				return this.outerLocalVariables[i];
		return null;
	}

	// Return the actual method binding devoid of synthetics. 
	public MethodBinding getMethodBinding() {
		if (this.actualMethodBinding == null) {
			if (this.binding != null) {
				// Get rid of the synthetic arguments added via addSyntheticArgument()
				TypeBinding[] newParams = null;
				if (this.binding instanceof SyntheticMethodBinding && this.outerLocalVariables.length > 0) {
					newParams = new TypeBinding[this.binding.parameters.length - this.outerLocalVariables.length];
					System.arraycopy(this.binding.parameters, this.outerLocalVariables.length, newParams, 0, newParams.length);
				} else {
					newParams = this.binding.parameters;
				}
				this.actualMethodBinding = new MethodBinding(this.binding.modifiers, this.binding.selector,
						this.binding.returnType, newParams, this.binding.thrownExceptions, this.binding.declaringClass);
				this.actualMethodBinding.tagBits = this.binding.tagBits;
			} else {
				this.actualMethodBinding = new ProblemMethodBinding(CharOperation.NO_CHAR, null, ProblemReasons.NoSuchSingleAbstractMethod);
			}
		}
		return this.actualMethodBinding;
	}

	public int diagnosticsSourceEnd() {
		return this.body instanceof Block ? this.arrowPosition : this.sourceEnd;
	}

	public TypeBinding[] getMarkerInterfaces() {
		if (this.expectedType instanceof IntersectionTypeBinding18) {
			Set markerBindings = new LinkedHashSet();
			TypeBinding[] intersectionTypes = ((IntersectionTypeBinding18)this.expectedType).intersectingTypes;
			for (int i = 0,max = intersectionTypes.length; i < max; i++) {
				TypeBinding typeBinding = intersectionTypes[i];
				MethodBinding methodBinding = typeBinding.getSingleAbstractMethod(this.scope, true);
				// Why doesn't getSingleAbstractMethod do as the javadoc says, and return null
				// when it is not a SAM type
				if (!(methodBinding instanceof ProblemMethodBinding && ((ProblemMethodBinding)methodBinding).problemId()==ProblemReasons.NoSuchSingleAbstractMethod)) {
					continue;
				}
				if (typeBinding.id == TypeIds.T_JavaIoSerializable) {
					// Serializable is captured as a bitflag
					continue;
				}
				markerBindings.add(typeBinding);
			}
			if (markerBindings.size() > 0) {
				return (TypeBinding[])markerBindings.toArray(new TypeBinding[markerBindings.size()]);
			}
		}
		return null;
	}

	public ReferenceBinding getTypeBinding() {
	
		if (this.classType != null || this.resolvedType == null)
			return null;
		
		class LambdaTypeBinding extends ReferenceBinding {
			public MethodBinding[] methods() {
				return new MethodBinding [] { getMethodBinding() };
			}
			public char[] sourceName() {
				return TypeConstants.LAMBDA_TYPE;
			}
			public ReferenceBinding superclass() {
				return LambdaExpression.this.scope.getJavaLangObject();
			}
			public ReferenceBinding[] superInterfaces() {
				return new ReferenceBinding[] { (ReferenceBinding) LambdaExpression.this.resolvedType };
			}
			@Override
			public char[] computeUniqueKey() {
				return LambdaExpression.this.descriptor.declaringClass.computeUniqueKey();
			}
			public String toString() {
				StringBuffer output = new StringBuffer("()->{} implements "); //$NON-NLS-1$
				output.append(LambdaExpression.this.descriptor.declaringClass.sourceName());
				output.append('.');
				output.append(LambdaExpression.this.descriptor.toString());
				return output.toString();
			}
		}
		return this.classType = new LambdaTypeBinding();
	}
}
