package org.checkerframework.checker.index.upperbound;

import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.util.TreePath;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.util.Elements;
import org.checkerframework.checker.index.BaseAnnotatedTypeFactoryForIndexChecker;
import org.checkerframework.checker.index.IndexChecker;
import org.checkerframework.checker.index.IndexMethodIdentifier;
import org.checkerframework.checker.index.IndexUtil;
import org.checkerframework.checker.index.OffsetDependentTypesHelper;
import org.checkerframework.checker.index.inequality.LessThanAnnotatedTypeFactory;
import org.checkerframework.checker.index.inequality.LessThanChecker;
import org.checkerframework.checker.index.lowerbound.LowerBoundAnnotatedTypeFactory;
import org.checkerframework.checker.index.lowerbound.LowerBoundChecker;
import org.checkerframework.checker.index.qual.IndexFor;
import org.checkerframework.checker.index.qual.IndexOrHigh;
import org.checkerframework.checker.index.qual.IndexOrLow;
import org.checkerframework.checker.index.qual.LTEqLengthOf;
import org.checkerframework.checker.index.qual.LTLengthOf;
import org.checkerframework.checker.index.qual.LTOMLengthOf;
import org.checkerframework.checker.index.qual.LengthOf;
import org.checkerframework.checker.index.qual.NegativeIndexFor;
import org.checkerframework.checker.index.qual.PolyIndex;
import org.checkerframework.checker.index.qual.PolyUpperBound;
import org.checkerframework.checker.index.qual.SameLen;
import org.checkerframework.checker.index.qual.SearchIndexFor;
import org.checkerframework.checker.index.qual.UpperBoundBottom;
import org.checkerframework.checker.index.qual.UpperBoundLiteral;
import org.checkerframework.checker.index.qual.UpperBoundUnknown;
import org.checkerframework.checker.index.samelen.SameLenAnnotatedTypeFactory;
import org.checkerframework.checker.index.samelen.SameLenChecker;
import org.checkerframework.checker.index.searchindex.SearchIndexAnnotatedTypeFactory;
import org.checkerframework.checker.index.searchindex.SearchIndexChecker;
import org.checkerframework.checker.index.substringindex.SubstringIndexAnnotatedTypeFactory;
import org.checkerframework.checker.index.substringindex.SubstringIndexChecker;
import org.checkerframework.checker.index.upperbound.UBQualifier.LessThanLengthOf;
import org.checkerframework.checker.index.upperbound.UBQualifier.UpperBoundLiteralQualifier;
import org.checkerframework.checker.index.upperbound.UBQualifier.UpperBoundUnknownQualifier;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.common.basetype.BaseTypeChecker;
import org.checkerframework.common.value.ValueAnnotatedTypeFactory;
import org.checkerframework.common.value.ValueChecker;
import org.checkerframework.common.value.ValueCheckerUtils;
import org.checkerframework.common.value.qual.BottomVal;
import org.checkerframework.common.value.util.Range;
import org.checkerframework.dataflow.cfg.node.Node;
import org.checkerframework.dataflow.expression.JavaExpression;
import org.checkerframework.framework.flow.CFAbstractStore;
import org.checkerframework.framework.flow.CFStore;
import org.checkerframework.framework.flow.CFValue;
import org.checkerframework.framework.type.AnnotatedTypeFactory;
import org.checkerframework.framework.type.AnnotatedTypeMirror;
import org.checkerframework.framework.type.ElementQualifierHierarchy;
import org.checkerframework.framework.type.QualifierHierarchy;
import org.checkerframework.framework.type.treeannotator.ListTreeAnnotator;
import org.checkerframework.framework.type.treeannotator.TreeAnnotator;
import org.checkerframework.framework.type.typeannotator.ListTypeAnnotator;
import org.checkerframework.framework.type.typeannotator.TypeAnnotator;
import org.checkerframework.framework.util.JavaExpressionParseUtil.JavaExpressionParseException;
import org.checkerframework.framework.util.dependenttypes.DependentTypesHelper;
import org.checkerframework.javacutil.AnnotationBuilder;
import org.checkerframework.javacutil.AnnotationMirrorSet;
import org.checkerframework.javacutil.AnnotationUtils;
import org.checkerframework.javacutil.TreeUtils;
import org.checkerframework.javacutil.TypeSystemError;
import org.plumelib.util.IPair;

/**
 * Implements the introduction rules for the Upper Bound Checker.
 *
 * <p>Rules implemented by this class:
 *
 * <ul>
 *   <li>1. Math.min has unusual semantics that combines annotations for the UBC.
 *   <li>2. The return type of Random.nextInt depends on the argument, but is not equal to it, so a
 *       polymorphic qualifier is insufficient.
 *   <li>3. Unary negation on a NegativeIndexFor (from the SearchIndex Checker) results in a
 *       LTLengthOf for the same arrays.
 *   <li>4. Right shifting by a constant between 0 and 30 preserves the type of the left side
 *       expression.
 *   <li>5. If either argument to a bitwise and expression is non-negative, the and expression
 *       retains that argument's upperbound type. If both are non-negative, the result of the
 *       expression is the GLB of the two.
 *   <li>6. if numerator &ge; 0, then numerator % divisor &le; numerator
 *   <li>7. if divisor &ge; 0, then numerator % divisor &lt; divisor
 *   <li>8. If the numerator is an array length access of an array with non-zero length, and the
 *       divisor is greater than one, glb the result with an LTL of the array.
 *   <li>9. if numeratorTree is a + b and divisor greater than 1, and a and b are less than the
 *       length of some sequence, then (a + b) / divisor is less than the length of that sequence.
 *   <li>10. Special handling for Math.random: Math.random() * array.length is LTL array.
 * </ul>
 */
public class UpperBoundAnnotatedTypeFactory extends BaseAnnotatedTypeFactoryForIndexChecker {

  /** The @{@link UpperBoundUnknown} annotation. */
  public final AnnotationMirror UNKNOWN =
      AnnotationBuilder.fromClass(elements, UpperBoundUnknown.class);

  /** The @{@link UpperBoundBottom} annotation. */
  public final AnnotationMirror BOTTOM =
      AnnotationBuilder.fromClass(elements, UpperBoundBottom.class);

  /** The @{@link PolyUpperBound} annotation. */
  public final AnnotationMirror POLY = AnnotationBuilder.fromClass(elements, PolyUpperBound.class);

  /** The @{@link UpperBoundLiteral}(-1) annotation. */
  @SuppressWarnings("this-escape")
  public final AnnotationMirror NEGATIVEONE =
      new AnnotationBuilder(getProcessingEnv(), UpperBoundLiteral.class)
          .setValue("value", -1)
          .build();

  /** The @{@link UpperBoundLiteral}(0) annotation. */
  @SuppressWarnings("this-escape")
  public final AnnotationMirror ZERO =
      new AnnotationBuilder(getProcessingEnv(), UpperBoundLiteral.class)
          .setValue("value", 0)
          .build();

  /** The @{@link UpperBoundLiteral}(1) annotation. */
  @SuppressWarnings("this-escape")
  public final AnnotationMirror ONE =
      new AnnotationBuilder(getProcessingEnv(), UpperBoundLiteral.class)
          .setValue("value", 1)
          .build();

  /** The NegativeIndexFor.value element/field. */
  public final ExecutableElement negativeIndexForValueElement =
      TreeUtils.getMethod(NegativeIndexFor.class, "value", 0, processingEnv);

  /** The SameLen.value element/field. */
  public final ExecutableElement sameLenValueElement =
      TreeUtils.getMethod(SameLen.class, "value", 0, processingEnv);

  /** The LTLengthOf.value element/field. */
  public final ExecutableElement ltLengthOfValueElement =
      TreeUtils.getMethod(LTLengthOf.class, "value", 0, processingEnv);

  /** The LTLengthOf.offset element/field. */
  public final ExecutableElement ltLengthOfOffsetElement =
      TreeUtils.getMethod(LTLengthOf.class, "offset", 0, processingEnv);

  /** Predicates about what method an invocation is calling. */
  private final IndexMethodIdentifier imf;

  /** Create a new UpperBoundAnnotatedTypeFactory. */
  @SuppressWarnings("this-escape")
  public UpperBoundAnnotatedTypeFactory(BaseTypeChecker checker) {
    super(checker);

    addAliasedTypeAnnotation(IndexFor.class, LTLengthOf.class, true);
    addAliasedTypeAnnotation(IndexOrLow.class, LTLengthOf.class, true);
    addAliasedTypeAnnotation(IndexOrHigh.class, LTEqLengthOf.class, true);
    addAliasedTypeAnnotation(SearchIndexFor.class, LTLengthOf.class, true);
    addAliasedTypeAnnotation(NegativeIndexFor.class, LTLengthOf.class, true);
    addAliasedTypeAnnotation(LengthOf.class, LTEqLengthOf.class, true);
    addAliasedTypeAnnotation(PolyIndex.class, POLY);

    imf = new IndexMethodIdentifier(this);

    this.postInit();
  }

  /** Gets a helper object that holds references to methods with special handling. */
  IndexMethodIdentifier getMethodIdentifier() {
    return imf;
  }

  @Override
  protected Set<Class<? extends Annotation>> createSupportedTypeQualifiers() {
    // Because the Index Checker is a subclass, the qualifiers have to be explicitly defined.
    return new LinkedHashSet<>(
        Arrays.asList(
            UpperBoundUnknown.class,
            LTEqLengthOf.class,
            LTLengthOf.class,
            LTOMLengthOf.class,
            UpperBoundLiteral.class,
            UpperBoundBottom.class,
            PolyUpperBound.class));
  }

  /**
   * Provides a way to query the Constant Value Checker, which computes the values of expressions
   * known at compile time (constant propagation and folding).
   */
  ValueAnnotatedTypeFactory getValueAnnotatedTypeFactory() {
    return getTypeFactoryOfSubchecker(ValueChecker.class);
  }

  /**
   * Provides a way to query the Search Index Checker, which helps the Index Checker type the
   * results of calling the JDK's binary search methods correctly.
   */
  private SearchIndexAnnotatedTypeFactory getSearchIndexAnnotatedTypeFactory() {
    return getTypeFactoryOfSubchecker(SearchIndexChecker.class);
  }

  /**
   * Gets the annotated type factory of the Substring Index Checker running along with the Upper
   * Bound checker, allowing it to refine the upper bounds of expressions annotated by Substring
   * Index Checker annotations.
   */
  SubstringIndexAnnotatedTypeFactory getSubstringIndexAnnotatedTypeFactory() {
    return getTypeFactoryOfSubchecker(SubstringIndexChecker.class);
  }

  /**
   * Provides a way to query the SameLen (same length) Checker, which determines the relationships
   * among the lengths of arrays.
   */
  SameLenAnnotatedTypeFactory getSameLenAnnotatedTypeFactory() {
    return getTypeFactoryOfSubchecker(SameLenChecker.class);
  }

  /**
   * Provides a way to query the Lower Bound Checker, which determines whether each integer in the
   * program is non-negative or not, and checks that no possibly negative integers are used to
   * access arrays.
   */
  LowerBoundAnnotatedTypeFactory getLowerBoundAnnotatedTypeFactory() {
    return getTypeFactoryOfSubchecker(LowerBoundChecker.class);
  }

  /** Returns the LessThan Checker's annotated type factory. */
  public LessThanAnnotatedTypeFactory getLessThanAnnotatedTypeFactory() {
    return getTypeFactoryOfSubchecker(LessThanChecker.class);
  }

  @Override
  public void addComputedTypeAnnotations(Element element, AnnotatedTypeMirror type) {
    super.addComputedTypeAnnotations(element, type);
    if (element != null && !ajavaTypes.isParsing()) {
      AnnotatedTypeMirror valueType = getValueAnnotatedTypeFactory().getAnnotatedType(element);
      addUpperBoundTypeFromValueType(valueType, type);
    }
  }

  @Override
  public void addComputedTypeAnnotations(Tree tree, AnnotatedTypeMirror type, boolean iUseFlow) {
    super.addComputedTypeAnnotations(tree, type, iUseFlow);
    // If dataflow shouldn't be used to compute this type, then do not use the result from
    // the Value Checker, because dataflow is used to compute that type.  (Without this,
    // "int i = 1; --i;" fails.)
    if (iUseFlow && tree != null && !ajavaTypes.isParsing() && TreeUtils.isExpressionTree(tree)) {
      AnnotatedTypeMirror valueType = getValueAnnotatedTypeFactory().getAnnotatedType(tree);
      addUpperBoundTypeFromValueType(valueType, type);
    }
  }

  /**
   * Checks if valueType contains a {@link org.checkerframework.common.value.qual.BottomVal}
   * annotation. If so, adds an {@link UpperBoundBottom} annotation to type.
   *
   * @param valueType annotated type from the {@link ValueAnnotatedTypeFactory}
   * @param type annotated type from this factory that is side effected
   */
  private void addUpperBoundTypeFromValueType(
      AnnotatedTypeMirror valueType, AnnotatedTypeMirror type) {
    if (containsSameByClass(valueType.getPrimaryAnnotations(), BottomVal.class)) {
      type.replaceAnnotation(BOTTOM);
    }
  }

  @Override
  protected TypeAnnotator createTypeAnnotator() {
    return new ListTypeAnnotator(new UpperBoundTypeAnnotator(this), super.createTypeAnnotator());
  }

  /**
   * Performs pre-processing on annotations written by users, replacing illegal annotations by legal
   * ones.
   */
  private class UpperBoundTypeAnnotator extends TypeAnnotator {

    private UpperBoundTypeAnnotator(AnnotatedTypeFactory atypeFactory) {
      super(atypeFactory);
    }

    @Override
    protected Void scan(AnnotatedTypeMirror type, Void aVoid) {
      // If there is an LTLengthOf annotation whose argument lengths don't match, replace it
      // with bottom.
      AnnotationMirror anm = type.getPrimaryAnnotation(LTLengthOf.class);
      if (anm != null) {
        List<String> sequences =
            AnnotationUtils.getElementValueArray(anm, ltLengthOfValueElement, String.class);
        List<String> offsets =
            AnnotationUtils.getElementValueArray(
                anm, ltLengthOfOffsetElement, String.class, Collections.emptyList());
        if (sequences != null
            && offsets != null
            && sequences.size() != offsets.size()
            && !offsets.isEmpty()) {
          // Cannot use type.replaceAnnotation because it will call isSubtype, which will
          // try to process the annotation and throw an error.
          type.clearPrimaryAnnotations();
          type.addAnnotation(BOTTOM);
        }
      }
      return super.scan(type, aVoid);
    }
  }

  @Override
  protected DependentTypesHelper createDependentTypesHelper() {
    return new OffsetDependentTypesHelper(this);
  }

  /**
   * Queries the SameLen Checker to return the type that the SameLen Checker associates with the
   * given tree.
   */
  public @Nullable AnnotationMirror sameLenAnnotationFromTree(Tree tree) {
    AnnotatedTypeMirror sameLenType = getSameLenAnnotatedTypeFactory().getAnnotatedType(tree);
    return sameLenType.getPrimaryAnnotation(SameLen.class);
  }

  // Wrapper methods for accessing the IndexMethodIdentifier.

  public boolean isMathMin(Tree methodTree) {
    return imf.isMathMin(methodTree);
  }

  /**
   * Returns true if the tree is for {@code Random.nextInt(int)}.
   *
   * @param methodTree a tree to check
   * @return true iff the tree is for {@code Random.nextInt(int)}
   */
  public boolean isRandomNextInt(Tree methodTree) {
    return imf.isRandomNextInt(methodTree, processingEnv);
  }

  /**
   * Creates a new @LTLengthOf annotation.
   *
   * @param names the arguments to @LTLengthOf
   * @return a new @LTLengthOf annotation with the given arguments
   */
  AnnotationMirror createLTLengthOfAnnotation(String... names) {
    if (names == null || names.length == 0) {
      throw new TypeSystemError(
          "createLTLengthOfAnnotation: bad argument %s", Arrays.toString(names));
    }
    AnnotationBuilder builder = new AnnotationBuilder(getProcessingEnv(), LTLengthOf.class);
    builder.setValue("value", names);
    return builder.build();
  }

  /**
   * Creates a new @LTEqLengthOf annotation.
   *
   * @param names the arguments to @LTEqLengthOf
   * @return a new @LTEqLengthOf annotation with the given arguments
   */
  AnnotationMirror createLTEqLengthOfAnnotation(String... names) {
    if (names == null || names.length == 0) {
      throw new TypeSystemError(
          "createLTEqLengthOfAnnotation: bad argument %s", Arrays.toString(names));
    }
    AnnotationBuilder builder = new AnnotationBuilder(getProcessingEnv(), LTEqLengthOf.class);
    builder.setValue("value", names);
    return builder.build();
  }

  /**
   * Returns true iff the given node has the passed Lower Bound qualifier according to the LBC. The
   * last argument should be Positive.class, NonNegative.class, or GTENegativeOne.class.
   *
   * @param node the given node
   * @param classOfType one of Positive.class, NonNegative.class, or GTENegativeOne.class
   * @return true iff the given node has the passed Lower Bound qualifier according to the LBC
   */
  public boolean hasLowerBoundTypeByClass(Node node, Class<? extends Annotation> classOfType) {
    return areSameByClass(
        getLowerBoundAnnotatedTypeFactory()
            .getAnnotatedType(node.getTree())
            .getPrimaryAnnotationInHierarchy(getLowerBoundAnnotatedTypeFactory().UNKNOWN),
        classOfType);
  }

  @Override
  protected QualifierHierarchy createQualifierHierarchy() {
    return new UpperBoundQualifierHierarchy(this.getSupportedTypeQualifiers(), elements);
  }

  /** The qualifier hierarchy for the upperbound type system. */
  protected final class UpperBoundQualifierHierarchy extends ElementQualifierHierarchy {
    /**
     * Creates an UpperBoundQualifierHierarchy from the given classes.
     *
     * @param qualifierClasses classes of annotations that are the qualifiers
     * @param elements element utils
     */
    UpperBoundQualifierHierarchy(
        Collection<Class<? extends Annotation>> qualifierClasses, Elements elements) {
      super(qualifierClasses, elements, UpperBoundAnnotatedTypeFactory.this);
    }

    @Override
    public AnnotationMirror greatestLowerBoundQualifiers(AnnotationMirror a1, AnnotationMirror a2) {
      UBQualifier a1Obj = UBQualifier.createUBQualifier(a1, (IndexChecker) checker);
      UBQualifier a2Obj = UBQualifier.createUBQualifier(a2, (IndexChecker) checker);
      UBQualifier glb = a1Obj.glb(a2Obj);
      return convertUBQualifierToAnnotation(glb);
    }

    /**
     * Determines the least upper bound of a1 and a2. If a1 and a2 are both the same type of Value
     * annotation, then the LUB is the result of taking the intersection of values from both a1 and
     * a2.
     *
     * @return the least upper bound of a1 and a2
     */
    @Override
    public AnnotationMirror leastUpperBoundQualifiers(AnnotationMirror a1, AnnotationMirror a2) {
      UBQualifier a1Obj = UBQualifier.createUBQualifier(a1, (IndexChecker) checker);
      UBQualifier a2Obj = UBQualifier.createUBQualifier(a2, (IndexChecker) checker);
      UBQualifier lub = a1Obj.lub(a2Obj);
      return convertUBQualifierToAnnotation(lub);
    }

    @Override
    public AnnotationMirror widenedUpperBound(
        AnnotationMirror newQualifier, AnnotationMirror previousQualifier) {
      UBQualifier a1Obj = UBQualifier.createUBQualifier(newQualifier, (IndexChecker) checker);
      UBQualifier a2Obj = UBQualifier.createUBQualifier(previousQualifier, (IndexChecker) checker);
      UBQualifier lub = a1Obj.widenUpperBound(a2Obj);
      return convertUBQualifierToAnnotation(lub);
    }

    @Override
    public int numberOfIterationsBeforeWidening() {
      return 10;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Computes subtyping as per the subtyping in the qualifier hierarchy structure unless both
     * annotations have the same class. In this case, rhs is a subtype of lhs iff rhs contains every
     * element of lhs.
     */
    @Override
    public boolean isSubtypeQualifiers(AnnotationMirror subAnno, AnnotationMirror superAnno) {
      UBQualifier subtypeQual = UBQualifier.createUBQualifier(subAnno, (IndexChecker) checker);
      UBQualifier supertypeQual = UBQualifier.createUBQualifier(superAnno, (IndexChecker) checker);
      return subtypeQual.isSubtype(supertypeQual);
    }
  }

  @Override
  public TreeAnnotator createTreeAnnotator() {
    return new ListTreeAnnotator(new UpperBoundTreeAnnotator(this), super.createTreeAnnotator());
  }

  protected class UpperBoundTreeAnnotator extends TreeAnnotator {

    public UpperBoundTreeAnnotator(UpperBoundAnnotatedTypeFactory factory) {
      super(factory);
    }

    /**
     * This exists for Math.min and Random.nextInt, which must be special-cased.
     *
     * <ul>
     *   <li>Math.min has unusual semantics that combines annotations for the UBC.
     *   <li>The return type of Random.nextInt depends on the argument, but is not equal to it, so a
     *       polymorphic qualifier is insufficient.
     * </ul>
     *
     * Other methods should not be special-cased here unless there is a compelling reason to do so.
     */
    @Override
    public Void visitMethodInvocation(MethodInvocationTree tree, AnnotatedTypeMirror type) {
      if (isMathMin(tree)) {
        AnnotatedTypeMirror leftType = getAnnotatedType(tree.getArguments().get(0));
        AnnotatedTypeMirror rightType = getAnnotatedType(tree.getArguments().get(1));

        type.replaceAnnotation(
            qualHierarchy.greatestLowerBoundShallow(
                leftType.getPrimaryAnnotationInHierarchy(UNKNOWN),
                leftType.getUnderlyingType(),
                rightType.getPrimaryAnnotationInHierarchy(UNKNOWN),
                rightType.getUnderlyingType()));
      }
      if (isRandomNextInt(tree)) {
        AnnotatedTypeMirror argType = getAnnotatedType(tree.getArguments().get(0));
        AnnotationMirror anno = argType.getPrimaryAnnotationInHierarchy(UNKNOWN);
        UBQualifier qualifier = UBQualifier.createUBQualifier(anno, (IndexChecker) checker);
        qualifier = qualifier.plusOffset(1);
        type.replaceAnnotation(convertUBQualifierToAnnotation(qualifier));
      }
      if (imf.isIndexOfString(tree)) {
        // String#indexOf(String) and its variants that also take a String technically
        // return (and are annotated as) @LTEqLengthOf the receiver. However, the result is
        // always @LTLengthOf the receiver unless both the receiver and the target string
        // are the empty string: "".indexOf("") returns 0, which isn't an index into "". So,
        // this special case modifies the return type of these methods if either the
        // parameter or the receiver is known (by the Value Checker) to not be the empty
        // string. There are three ways the Value Checker might have that information:
        // either string could have a @StringVal annotation whose value doesn't include "",
        // either could have an @ArrayLen annotation whose value doesn't contain zero, or
        // either could have an @ArrayLenRange annotation whose from value is any positive
        // integer.
        ValueAnnotatedTypeFactory vatf =
            ((UpperBoundAnnotatedTypeFactory) this.atypeFactory).getValueAnnotatedTypeFactory();
        AnnotatedTypeMirror argType = vatf.getAnnotatedType(tree.getArguments().get(0));
        AnnotatedTypeMirror receiverType = vatf.getReceiverType(tree);
        if (definitelyIsNotTheEmptyString(argType, vatf)
            || definitelyIsNotTheEmptyString(receiverType, vatf)) {
          String receiverName = JavaExpression.getReceiver(tree).toString();
          UBQualifier ltLengthOfReceiver = UBQualifier.createUBQualifier(receiverName, "0");
          AnnotationMirror currentReturnAnno = type.getPrimaryAnnotationInHierarchy(UNKNOWN);
          UBQualifier currentUBQualifier =
              UBQualifier.createUBQualifier(currentReturnAnno, (IndexChecker) checker);
          UBQualifier result = currentUBQualifier.glb(ltLengthOfReceiver);
          type.replaceAnnotation(convertUBQualifierToAnnotation(result));
        }
      }
      return super.visitMethodInvocation(tree, type);
    }

    /**
     * Returns true if the given Value Checker annotations guarantee that the annotated element is
     * not the empty string.
     *
     * @param atm an annotated type from the Value Checker
     * @param vatf the Value Annotated Type Factory
     * @return true iff atm contains a {@code StringVal} annotation whose value doesn't contain "",
     *     an {@code ArrayLen} annotation whose value doesn't contain 0, or an {@code ArrayLenRange}
     *     annotation whose from value is greater than 0
     */
    private boolean definitelyIsNotTheEmptyString(
        AnnotatedTypeMirror atm, ValueAnnotatedTypeFactory vatf) {
      AnnotationMirrorSet annos = atm.getPrimaryAnnotations();
      for (AnnotationMirror anno : annos) {
        switch (AnnotationUtils.annotationName(anno)) {
          case ValueAnnotatedTypeFactory.STRINGVAL_NAME:
            List<String> strings = vatf.getStringValues(anno);
            if (strings != null && !strings.contains("")) {
              return true;
            }
            break;
          case ValueAnnotatedTypeFactory.ARRAYLEN_NAME:
            List<Integer> lengths = vatf.getArrayLength(anno);
            if (lengths != null && !lengths.contains(0)) {
              return true;
            }
            break;
          default:
            Range range = vatf.getRange(anno);
            if (range != null && range.from > 0) {
              return true;
            }
            break;
        }
      }
      return false;
    }

    @Override
    public Void visitLiteral(LiteralTree tree, AnnotatedTypeMirror type) {
      // Could also handle long literals, but array indexes are always ints.
      if (tree.getKind() == Tree.Kind.INT_LITERAL) {
        type.addAnnotation(createLiteral(((Integer) tree.getValue()).intValue()));
      }
      return super.visitLiteral(tree, type);
    }

    /* Handles case 3. */
    @Override
    public Void visitUnary(UnaryTree tree, AnnotatedTypeMirror type) {
      // Dataflow refines this type if possible
      if (tree.getKind() == Tree.Kind.BITWISE_COMPLEMENT) {
        addAnnotationForBitwiseComplement(
            getSearchIndexAnnotatedTypeFactory().getAnnotatedType(tree.getExpression()), type);
      } else {
        type.addAnnotation(UNKNOWN);
      }
      return super.visitUnary(tree, type);
    }

    /**
     * If a type returned by an {@link SearchIndexAnnotatedTypeFactory} has a {@link
     * NegativeIndexFor} annotation, then refine the result to be {@link LTEqLengthOf}. This handles
     * this case:
     *
     * <pre>{@code
     * int i = Arrays.binarySearch(a, x);
     * if (i >= 0) {
     *     // do something
     * } else {
     *     i = ~i;
     *     // i is now @LTEqLengthOf("a"), because the bitwise complement of a NegativeIndexFor is an LTL.
     *     for (int j = 0; j < i; j++) {
     *          // j is now a valid index for "a"
     *     }
     * }
     * }</pre>
     *
     * @param searchIndexType the type of an expression in a bitwise complement. For instance, in
     *     {@code ~x}, this is the type of {@code x}.
     * @param typeDst the type of the entire bitwise complement expression. It is modified by this
     *     method.
     */
    private void addAnnotationForBitwiseComplement(
        AnnotatedTypeMirror searchIndexType, AnnotatedTypeMirror typeDst) {
      AnnotationMirror nif = searchIndexType.getPrimaryAnnotation(NegativeIndexFor.class);
      if (nif != null) {
        List<String> arrays =
            AnnotationUtils.getElementValueArray(nif, negativeIndexForValueElement, String.class);
        List<String> negativeOnes = Collections.nCopies(arrays.size(), "-1");
        UBQualifier qual = UBQualifier.createUBQualifier(arrays, negativeOnes);
        typeDst.addAnnotation(convertUBQualifierToAnnotation(qual));
      } else {
        typeDst.addAnnotation(UNKNOWN);
      }
    }

    @Override
    public Void visitCompoundAssignment(CompoundAssignmentTree tree, AnnotatedTypeMirror type) {
      // Dataflow refines this type if possible
      type.addAnnotation(UNKNOWN);
      return super.visitCompoundAssignment(tree, type);
    }

    @Override
    public Void visitBinary(BinaryTree tree, AnnotatedTypeMirror type) {
      // This implementation does NOT call getAnnotatedType on the left or right operands.
      // Doing so would lead to re-examination of subexpressions many times (which is too
      // slow).

      // A few small rules for addition/subtraction by 0/1, etc.
      if (TreeUtils.isStringConcatenation(tree)) {
        type.addAnnotation(UNKNOWN);
        return super.visitBinary(tree, type);
      }

      ExpressionTree left = tree.getLeftOperand();
      ExpressionTree right = tree.getRightOperand();
      switch (tree.getKind()) {
        case PLUS:
        case MINUS:
          // Dataflow refines this type if possible
          type.addAnnotation(UNKNOWN);
          break;
        case MULTIPLY:
          addAnnotationForMultiply(left, right, type);
          break;
        case DIVIDE:
          addAnnotationForDivide(left, right, type);
          break;
        case REMAINDER:
          addAnnotationForRemainder(left, right, type);
          break;
        case AND:
          addAnnotationForAnd(left, right, type);
          break;
        case RIGHT_SHIFT:
        case UNSIGNED_RIGHT_SHIFT:
          addAnnotationForRightShift(left, right, type);
          break;
        default:
          break;
      }
      return super.visitBinary(tree, type);
    }

    /**
     * Infers upper-bound annotation for {@code left >> right} and {@code left >>> right} (case 4).
     */
    private void addAnnotationForRightShift(
        ExpressionTree left, ExpressionTree right, AnnotatedTypeMirror type) {
      LowerBoundAnnotatedTypeFactory lowerBoundATF = getLowerBoundAnnotatedTypeFactory();
      if (lowerBoundATF.isNonNegative(left)) {
        AnnotationMirror annotation =
            getAnnotatedType(left).getPrimaryAnnotationInHierarchy(UNKNOWN);
        // For non-negative numbers, right shift is equivalent to division by a power of
        // two.
        // The range of the shift amount is limited to 0..30 to avoid overflows and int/long
        // differences.
        Long shiftAmount = ValueCheckerUtils.getExactValue(right, getValueAnnotatedTypeFactory());
        if (shiftAmount != null && shiftAmount >= 0 && shiftAmount < Integer.SIZE - 1) {
          int divisor = 1 << shiftAmount;
          // Support average by shift just like for division
          UBQualifier plusDivQualifier = plusTreeDivideByVal(divisor, left);
          if (!plusDivQualifier.isUnknown()) {
            UBQualifier qualifier =
                UBQualifier.createUBQualifier(annotation, (IndexChecker) checker);
            qualifier = qualifier.glb(plusDivQualifier);
            annotation = convertUBQualifierToAnnotation(qualifier);
          }
        }
        type.addAnnotation(annotation);
      }
    }

    /**
     * If either argument is non-negative, the and expression retains that argument's upperbound
     * type. If both are non-negative, the result of the expression is the GLB of the two (case 5).
     */
    private void addAnnotationForAnd(
        ExpressionTree left, ExpressionTree right, AnnotatedTypeMirror type) {
      LowerBoundAnnotatedTypeFactory lowerBoundATF = getLowerBoundAnnotatedTypeFactory();
      AnnotatedTypeMirror leftType = getAnnotatedType(left);
      AnnotationMirror leftResultAnno = UNKNOWN;
      if (lowerBoundATF.isNonNegative(left)) {
        leftResultAnno = leftType.getPrimaryAnnotationInHierarchy(UNKNOWN);
      }

      AnnotatedTypeMirror rightType = getAnnotatedType(right);
      AnnotationMirror rightResultAnno = UNKNOWN;
      if (lowerBoundATF.isNonNegative(right)) {
        rightResultAnno = rightType.getPrimaryAnnotationInHierarchy(UNKNOWN);
      }

      type.addAnnotation(
          qualHierarchy.greatestLowerBoundShallow(
              leftResultAnno,
              leftType.getUnderlyingType(),
              rightResultAnno,
              rightType.getUnderlyingType()));
    }

    /** Gets a sequence tree for a length access tree, or null if it is not a length access. */
    private @Nullable ExpressionTree getLengthSequenceTree(ExpressionTree lengthTree) {
      return IndexUtil.getLengthSequenceTree(lengthTree, imf, processingEnv);
    }

    /**
     * Infers upper-bound annotation for {@code numerator % divisor}. There are two cases where an
     * upperbound type is inferred:
     *
     * <ul>
     *   <li>6. if numerator &ge; 0, then numerator % divisor &le; numerator
     *   <li>7. if divisor &ge; 0, then numerator % divisor &lt; divisor
     * </ul>
     */
    private void addAnnotationForRemainder(
        ExpressionTree numeratorTree, ExpressionTree divisorTree, AnnotatedTypeMirror resultType) {
      LowerBoundAnnotatedTypeFactory lowerBoundATF = getLowerBoundAnnotatedTypeFactory();
      UBQualifier result = UpperBoundUnknownQualifier.UNKNOWN;
      // if numerator >= 0, then numerator%divisor <= numerator
      if (lowerBoundATF.isNonNegative(numeratorTree)) {
        result =
            UBQualifier.createUBQualifier(
                getAnnotatedType(numeratorTree), UNKNOWN, (IndexChecker) checker);
      }
      // if divisor >= 0, then numerator%divisor < divisor
      if (lowerBoundATF.isNonNegative(divisorTree)) {
        UBQualifier divisor =
            UBQualifier.createUBQualifier(
                getAnnotatedType(divisorTree), UNKNOWN, (IndexChecker) checker);
        result = result.glb(divisor.plusOffset(1));
      }
      resultType.addAnnotation(convertUBQualifierToAnnotation(result));
    }

    /**
     * Implements two cases (8 and 9):
     *
     * <ul>
     *   <li>8. If the numerator is an array length access of an array with non-zero length, and the
     *       divisor is greater than one, glb the result with an LTL of the array.
     *   <li>9. if numeratorTree is a + b and divisor greater than 1, and a and b are less than the
     *       length of some sequence, then (a + b) / divisor is less than the length of that
     *       sequence.
     * </ul>
     */
    private void addAnnotationForDivide(
        ExpressionTree numeratorTree, ExpressionTree divisorTree, AnnotatedTypeMirror resultType) {

      Long divisor = ValueCheckerUtils.getExactValue(divisorTree, getValueAnnotatedTypeFactory());
      if (divisor == null) {
        resultType.addAnnotation(UNKNOWN);
        return;
      }

      UBQualifier result = UpperBoundUnknownQualifier.UNKNOWN;
      UBQualifier numerator =
          UBQualifier.createUBQualifier(
              getAnnotatedType(numeratorTree), UNKNOWN, (IndexChecker) checker);
      if (numerator.isLessThanLengthQualifier()) {
        result = ((LessThanLengthOf) numerator).divide(divisor.intValue());
      }
      result = result.glb(plusTreeDivideByVal(divisor.intValue(), numeratorTree));

      ExpressionTree numeratorSequenceTree = getLengthSequenceTree(numeratorTree);
      // If the numerator is an array length access of an array with non-zero length, and the
      // divisor is greater than one, glb the result with an LTL of the array.
      if (numeratorSequenceTree != null && divisor > 1) {
        String arrayName = numeratorSequenceTree.toString();
        int minlen =
            getValueAnnotatedTypeFactory()
                .getMinLenFromString(arrayName, numeratorTree, getPath(numeratorTree));
        if (minlen > 0) {
          result = result.glb(UBQualifier.createUBQualifier(arrayName, "0"));
        }
      }

      resultType.addAnnotation(convertUBQualifierToAnnotation(result));
    }

    /**
     * If {@code numeratorTree} is "a + b" and {@code divisor} is greater than 1, and a and b are
     * less than the length of some sequence, then "(a + b) / divisor" is less than the length of
     * that sequence.
     *
     * @param divisor the divisor
     * @param numeratorTree an addition tree that is divided by {@code divisor}
     * @return a qualifier for the division
     */
    private UBQualifier plusTreeDivideByVal(int divisor, ExpressionTree numeratorTree) {
      numeratorTree = TreeUtils.withoutParens(numeratorTree);
      if (divisor < 2 || numeratorTree.getKind() != Tree.Kind.PLUS) {
        return UpperBoundUnknownQualifier.UNKNOWN;
      }
      BinaryTree plusTree = (BinaryTree) numeratorTree;
      UBQualifier left =
          UBQualifier.createUBQualifier(
              getAnnotatedType(plusTree.getLeftOperand()), UNKNOWN, (IndexChecker) checker);
      UBQualifier right =
          UBQualifier.createUBQualifier(
              getAnnotatedType(plusTree.getRightOperand()), UNKNOWN, (IndexChecker) checker);
      if (left.isLessThanLengthQualifier() && right.isLessThanLengthQualifier()) {
        LessThanLengthOf leftLTL = (LessThanLengthOf) left;
        LessThanLengthOf rightLTL = (LessThanLengthOf) right;
        List<String> sequences = new ArrayList<>();
        for (String sequence : leftLTL.getSequences()) {
          if (rightLTL.isLessThanLengthOf(sequence) && leftLTL.isLessThanLengthOf(sequence)) {
            sequences.add(sequence);
          }
        }
        if (!sequences.isEmpty()) {
          return UBQualifier.createUBQualifier(sequences, Collections.emptyList());
        }
      }

      return UpperBoundUnknownQualifier.UNKNOWN;
    }

    /**
     * Special handling for Math.random: Math.random() * array.length is LTL array. Same for
     * Random.nextDouble. Case 10.
     */
    private boolean checkForMathRandomSpecialCase(
        ExpressionTree randTree, ExpressionTree seqLenTree, AnnotatedTypeMirror type) {

      ExpressionTree seqTree = getLengthSequenceTree(seqLenTree);

      if (randTree instanceof MethodInvocationTree && seqTree != null) {

        MethodInvocationTree mitree = (MethodInvocationTree) randTree;

        if (imf.isMathRandom(mitree, processingEnv)) {
          // Okay, so this is Math.random() * array.length, which must be NonNegative
          type.addAnnotation(createLTLengthOfAnnotation(seqTree.toString()));
          return true;
        }

        if (imf.isRandomNextDouble(mitree, processingEnv)) {
          // Okay, so this is Random.nextDouble() * array.length, which must be
          // NonNegative
          type.addAnnotation(createLTLengthOfAnnotation(seqTree.toString()));
          return true;
        }
      }

      return false;
    }

    private void addAnnotationForMultiply(
        ExpressionTree leftExpr, ExpressionTree rightExpr, AnnotatedTypeMirror type) {
      // Special handling for multiplying an array length by a random variable.
      if (checkForMathRandomSpecialCase(rightExpr, leftExpr, type)
          || checkForMathRandomSpecialCase(leftExpr, rightExpr, type)) {
        return;
      }
      type.addAnnotation(UNKNOWN);
    }
  }

  /**
   * Creates a @{@link UpperBoundLiteral} annotation.
   *
   * @param i the integer
   * @return a @{@link UpperBoundLiteral} annotation
   */
  public AnnotationMirror createLiteral(int i) {
    switch (i) {
      case -1:
        return NEGATIVEONE;
      case 0:
        return ZERO;
      case 1:
        return ONE;
      default:
        return new AnnotationBuilder(getProcessingEnv(), UpperBoundLiteral.class)
            .setValue("value", i)
            .build();
    }
  }

  /**
   * Convert the internal representation to an annotation.
   *
   * @param qualifier a UBQualifier
   * @return an annotation corresponding to the given qualifier
   */
  public AnnotationMirror convertUBQualifierToAnnotation(UBQualifier qualifier) {
    if (qualifier.isUnknown()) {
      return UNKNOWN;
    } else if (qualifier.isBottom()) {
      return BOTTOM;
    } else if (qualifier.isPoly()) {
      return POLY;
    } else if (qualifier.isLiteral()) {
      return createLiteral(((UpperBoundLiteralQualifier) qualifier).getValue());
    }

    LessThanLengthOf ltlQualifier = (LessThanLengthOf) qualifier;
    return ltlQualifier.convertToAnnotation(processingEnv);
  }

  @Nullable UBQualifier fromLessThan(ExpressionTree tree, TreePath treePath) {
    List<String> lessThanExpressions =
        getLessThanAnnotatedTypeFactory().getLessThanExpressions(tree);
    if (lessThanExpressions == null) {
      return null;
    }
    UBQualifier ubQualifier = fromLessThanOrEqual(tree, treePath, lessThanExpressions);
    if (ubQualifier != null) {
      return ubQualifier.plusOffset(1);
    }
    return null;
  }

  @Nullable UBQualifier fromLessThanOrEqual(ExpressionTree tree, TreePath treePath) {
    List<String> lessThanExpressions =
        getLessThanAnnotatedTypeFactory().getLessThanExpressions(tree);
    if (lessThanExpressions == null) {
      return null;
    }
    UBQualifier ubQualifier = fromLessThanOrEqual(tree, treePath, lessThanExpressions);
    return ubQualifier;
  }

  private @Nullable UBQualifier fromLessThanOrEqual(
      Tree tree, TreePath treePath, List<String> lessThanExpressions) {
    UBQualifier ubQualifier = null;
    for (String expression : lessThanExpressions) {
      IPair<JavaExpression, String> exprAndOffset;
      try {
        exprAndOffset = getExpressionAndOffsetFromJavaExpressionString(expression, treePath);
      } catch (JavaExpressionParseException e) {
        exprAndOffset = null;
      }
      if (exprAndOffset == null) {
        continue;
      }
      JavaExpression je = exprAndOffset.first;
      String offset = exprAndOffset.second;

      if (!CFAbstractStore.canInsertJavaExpression(je)) {
        continue;
      }
      CFStore store = getStoreBefore(tree);
      CFValue value = store.getValue(je);
      if (value != null && value.getAnnotations().size() == 1) {
        UBQualifier newUBQ =
            UBQualifier.createUBQualifier(
                qualHierarchy.findAnnotationInHierarchy(value.getAnnotations(), UNKNOWN),
                AnnotatedTypeFactory.negateConstant(offset),
                (IndexChecker) checker);
        if (ubQualifier == null) {
          ubQualifier = newUBQ;
        } else {
          ubQualifier = ubQualifier.glb(newUBQ);
        }
      }
    }
    return ubQualifier;
  }
}
