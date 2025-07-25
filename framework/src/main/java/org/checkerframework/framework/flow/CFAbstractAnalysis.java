package org.checkerframework.framework.flow;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.common.basetype.BaseTypeChecker;
import org.checkerframework.dataflow.analysis.ForwardAnalysisImpl;
import org.checkerframework.dataflow.analysis.TransferInput;
import org.checkerframework.dataflow.analysis.TransferResult;
import org.checkerframework.dataflow.cfg.ControlFlowGraph;
import org.checkerframework.dataflow.cfg.node.Node;
import org.checkerframework.dataflow.expression.FieldAccess;
import org.checkerframework.framework.source.SourceChecker;
import org.checkerframework.framework.type.AnnotatedTypeFactory;
import org.checkerframework.framework.type.AnnotatedTypeMirror;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedTypeVariable;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedWildcardType;
import org.checkerframework.framework.type.GenericAnnotatedTypeFactory;
import org.checkerframework.framework.type.QualifierHierarchy;
import org.checkerframework.framework.type.TypeHierarchy;
import org.checkerframework.framework.util.dependenttypes.DependentTypesHelper;
import org.checkerframework.javacutil.AnnotationMirrorSet;
import org.checkerframework.javacutil.BugInCF;
import org.checkerframework.javacutil.TypesUtils;

/**
 * {@link CFAbstractAnalysis} is an extensible org.checkerframework.dataflow analysis for the
 * Checker Framework that tracks the annotations using a flow-sensitive analysis. It uses an {@link
 * AnnotatedTypeFactory} to provide checker-specific logic how to combine types (e.g., what is the
 * type of a string concatenation, given the types of the two operands) and as an abstraction
 * function (e.g., determine the annotations on literals).
 *
 * <p>The purpose of this class is twofold: Firstly, it serves as factory for abstract values,
 * stores and the transfer function. Furthermore, it makes it easy for the transfer function and the
 * stores to access the {@link AnnotatedTypeFactory}, the qualifier hierarchy, etc.
 */
public abstract class CFAbstractAnalysis<
        V extends CFAbstractValue<V>,
        S extends CFAbstractStore<V, S>,
        T extends CFAbstractTransfer<V, S, T>>
    extends ForwardAnalysisImpl<V, S, T> {
  /** The qualifier hierarchy for which to track annotations. */
  protected final QualifierHierarchy qualHierarchy;

  /** The type hierarchy. */
  protected final TypeHierarchy typeHierarchy;

  /**
   * The dependent type helper used to standardize both annotations belonging to the type hierarchy,
   * and contract expressions.
   */
  protected final DependentTypesHelper dependentTypesHelper;

  /** A type factory that can provide static type annotations for AST Trees. */
  protected final GenericAnnotatedTypeFactory<V, S, T, ? extends CFAbstractAnalysis<V, S, T>>
      atypeFactory;

  /** A checker that contains command-line arguments and other information. */
  protected final SourceChecker checker;

  /**
   * A triple of field, value corresponding to the annotations on its declared type, value of its
   * initializer. The value of the initializer is {@code null} if the field does not have one.
   *
   * @param <V> type of value
   */
  public static class FieldInitialValue<V extends CFAbstractValue<V>> {

    /** A field access that corresponds to the declaration of a field. */
    public final FieldAccess fieldDecl;

    /** The value corresponding to the annotations on the declared type of the field. */
    public final V declared;

    /** The value of the initializer of the field, or null if no initializer exists. */
    public final @Nullable V initializer;

    /**
     * Creates a new FieldInitialValue.
     *
     * @param fieldDecl a field access that corresponds to the declaration of a field
     * @param declared value corresponding to the annotations on the declared type of {@code field}
     * @param initializer value of the initializer of {@code field}, or null if no initializer
     *     exists
     */
    public FieldInitialValue(FieldAccess fieldDecl, V declared, @Nullable V initializer) {
      this.fieldDecl = fieldDecl;
      this.declared = declared;
      this.initializer = initializer;
    }
  }

  /** Initial abstract types for fields. */
  protected final List<FieldInitialValue<V>> fieldValues;

  /** The associated processing environment. */
  protected final ProcessingEnvironment env;

  /** Instance of the types utility. */
  protected final Types types;

  /**
   * Create a CFAbstractAnalysis.
   *
   * @param checker a checker that contains command-line arguments and other information
   * @param factory an annotated type factory to introduce type and dataflow rules
   * @param maxCountBeforeWidening number of times a block can be analyzed before widening
   */
  @SuppressWarnings("this-escape")
  protected CFAbstractAnalysis(
      BaseTypeChecker checker,
      GenericAnnotatedTypeFactory<V, S, T, ? extends CFAbstractAnalysis<V, S, T>> factory,
      int maxCountBeforeWidening) {
    super(maxCountBeforeWidening);
    env = checker.getProcessingEnvironment();
    types = env.getTypeUtils();
    qualHierarchy = factory.getQualifierHierarchy();
    typeHierarchy = factory.getTypeHierarchy();
    dependentTypesHelper = factory.getDependentTypesHelper();
    this.atypeFactory = factory;
    this.checker = checker;
    this.transferFunction = createTransferFunction();
    this.fieldValues = new ArrayList<>();
  }

  /**
   * Create a CFAbstractAnalysis.
   *
   * @param checker a checker that contains command-line arguments and other information
   * @param factory an annotated type factory to introduce type and dataflow rules
   */
  protected CFAbstractAnalysis(
      BaseTypeChecker checker,
      GenericAnnotatedTypeFactory<V, S, T, ? extends CFAbstractAnalysis<V, S, T>> factory) {
    this(checker, factory, factory.getQualifierHierarchy().numberOfIterationsBeforeWidening());
  }

  /**
   * Analyze the given control flow graph.
   *
   * @param cfg control flow graph to analyze
   * @param fieldValues initial values of the fields
   */
  public void performAnalysis(ControlFlowGraph cfg, List<FieldInitialValue<V>> fieldValues) {
    this.fieldValues.clear();
    this.fieldValues.addAll(fieldValues);
    super.performAnalysis(cfg);
  }

  /**
   * A list of initial abstract values for the fields.
   *
   * @return a list of initial abstract values for the fields
   */
  public List<FieldInitialValue<V>> getFieldInitialValues() {
    return fieldValues;
  }

  /**
   * Returns the transfer function to be used by the analysis.
   *
   * @return the transfer function to be used by the analysis
   */
  public T createTransferFunction() {
    return atypeFactory.createFlowTransferFunction(this);
  }

  /**
   * Returns an empty store of the appropriate type.
   *
   * @return an empty store of the appropriate type
   */
  public abstract S createEmptyStore(boolean sequentialSemantics);

  /**
   * Returns an identical copy of the store {@code s}.
   *
   * @return an identical copy of the store {@code s}
   */
  public abstract S createCopiedStore(S s);

  /**
   * Creates an abstract value from the annotated type mirror. The value contains the set of primary
   * annotations on the type, unless the type is an AnnotatedWildcardType. For an
   * AnnotatedWildcardType, the annotations in the created value are the primary annotations on the
   * extends bound. See {@link CFAbstractValue} for an explanation.
   *
   * @param type the type to convert into an abstract value
   * @return an abstract value containing the given annotated {@code type}
   */
  public @Nullable V createAbstractValue(AnnotatedTypeMirror type) {
    AnnotationMirrorSet annos;
    if (type.getKind() == TypeKind.WILDCARD) {
      annos = ((AnnotatedWildcardType) type).getExtendsBound().getPrimaryAnnotations();
    } else if (TypesUtils.isCapturedTypeVariable(type.getUnderlyingType())) {
      annos = ((AnnotatedTypeVariable) type).getUpperBound().getPrimaryAnnotations();
    } else {
      annos = type.getPrimaryAnnotations();
    }
    return createAbstractValue(annos, type.getUnderlyingType());
  }

  /**
   * Returns an abstract value containing the given {@code annotations} and {@code underlyingType}.
   * Returns null if the annotation set has missing annotations.
   *
   * @param annotations the annotations for the result annotated type
   * @param underlyingType the unannotated type for the result annotated type
   * @return an abstract value containing the given {@code annotations} and {@code underlyingType}
   */
  public abstract @Nullable V createAbstractValue(
      AnnotationMirrorSet annotations, TypeMirror underlyingType);

  /** Default implementation for {@link #createAbstractValue(AnnotationMirrorSet, TypeMirror)}. */
  public @Nullable CFValue defaultCreateAbstractValue(
      CFAbstractAnalysis<CFValue, ?, ?> analysis,
      AnnotationMirrorSet annotations,
      TypeMirror underlyingType) {
    if (!CFAbstractValue.validateSet(annotations, underlyingType, atypeFactory)) {
      return null;
    }
    return new CFValue(analysis, annotations, underlyingType);
  }

  public TypeHierarchy getTypeHierarchy() {
    return typeHierarchy;
  }

  public GenericAnnotatedTypeFactory<V, S, T, ? extends CFAbstractAnalysis<V, S, T>>
      getTypeFactory() {
    return atypeFactory;
  }

  @Override
  protected TransferResult<V, S> callTransferFunction(Node node, TransferInput<V, S> input) {
    TransferResult<V, S> result;
    try {
      result = super.callTransferFunction(node, input);
    } catch (Throwable t) {
      throw new BugInCF(node.getTree(), t);
    }
    return result;
  }

  /**
   * Returns an abstract value containing an annotated type with the annotation {@code anno}, and
   * 'top' for all other hierarchies. The underlying type is {@code underlyingType}.
   *
   * @param anno the annotation for the result annotated type
   * @param underlyingType the unannotated type for the result annotated type
   * @return an abstract value with {@code anno} and {@code underlyingType}
   */
  public V createSingleAnnotationValue(AnnotationMirror anno, TypeMirror underlyingType) {
    QualifierHierarchy hierarchy = getTypeFactory().getQualifierHierarchy();
    AnnotationMirrorSet annos = new AnnotationMirrorSet();
    annos.addAll(hierarchy.getTopAnnotations());
    AnnotationMirror f = hierarchy.findAnnotationInSameHierarchy(annos, anno);
    annos.remove(f);
    annos.add(anno);
    return createAbstractValue(annos, underlyingType);
  }

  /**
   * Returns the types utility.
   *
   * @return {@link #types}
   */
  public Types getTypes() {
    return types;
  }

  /**
   * Returns the processing environment.
   *
   * @return {@link #env}
   */
  public ProcessingEnvironment getEnv() {
    return env;
  }
}
