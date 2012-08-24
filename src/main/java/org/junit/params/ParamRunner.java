package org.junit.params;

import org.junit.params.util.DataContext;

import org.junit.params.loader.FileType;
import org.junit.params.loader.Loader;
import org.junit.params.loader.LoaderFactory;

import org.junit.params.annotation.CustomLoader;
import org.junit.params.annotation.Param;
import org.junit.params.annotation.TestData;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;
import org.junit.experimental.theories.DataPoint;
import org.junit.experimental.theories.DataPoints;
import org.junit.experimental.theories.ParametersSuppliedBy;
import org.junit.experimental.theories.PotentialAssignment;
import org.junit.experimental.theories.PotentialAssignment.CouldNotGenerateValueException;
import org.junit.experimental.theories.internal.Assignments;
import org.junit.experimental.theories.internal.ParameterizedAssertionError;
import org.junit.internal.AssumptionViolatedException;
import org.junit.runner.Runner;
import org.junit.runners.BlockJUnit4ClassRunner;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.InitializationError;
import org.junit.runners.model.Statement;
import org.junit.runners.model.TestClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link BlockJUnit4ClassRunner} Runner implementation that adds support of input parameters as part of the {@link Test} annotation. 
 * This {@link BlockJUnit4ClassRunner} is an interesting mix of the current JUnits support for traditional {@link Test} methods and certain 
 * new things introduced in Theories. For example the Runner supports {@link DataPoint} , {@link DataPoints} and 
 * {@link ParametersSuppliedBy} annotations in the test class.
 * <br><br>
 * 
 * This {@link Runner} implementation supports input data in the same way as is entered by the user.
 * Currently, with Junit's Theories runner implementation, the test input is combined in all possible ways 
 * to generate a permutation and combination of test data.
 * This may not be required in many cases which this extended runner aims to eliminate.
 * 
 * <br><br>
 * <B>
 * A user can specify the test data at the class level as well, using the {@link TestData} annotation and override it at the method level. </B><br>
 * This is extremly beneficial in cases, where the user just wants to load the data once and then reuse it for all the test methods.
 * If the user wants, then he can always override the test data at the method level by specifying the {@link TestData} annotation at the method level.
 * <br><br>
 * In addition, this runner also introduces a new way for the user to specify the test data using {@link TestData} annotation.
 * 
 * <br><br>
 * There is also a {@link Param} annotation that is an extension of {@link ParametersSuppliedBy} annotation to 
 * handle boiler plate tasks on behalf of the user as well as supports additional functionality that eases the life of the user.
 * For eg. it supports Java PropertyEditors to automatically convert a String to the specified Object.
 * It also supports passing a Map to the test method that contains all the available test data key / value pairs for easy consumption by the user.
 *<br><br>
 * In order to use it, simply use the existing {@link Test} annotation along with any or no parameters on the test case 
 * and the runner will take care of supplying any parameters to the test method before running the test cases.
 *
 *@author Anuj Kumar
 */
public class ParamRunner extends BlockJUnit4ClassRunner {
    
    /**
     * An instance of logger associated with the test framework.
     */
    protected static final Logger PARAM_LOG = LoggerFactory.getLogger(ParamRunner.class);

    /**
     * 
     * Construct a new ParamRunner
     * @param klass the test class whose test methods needs to be executed
     * @throws InitializationError if any error occurs
     */
    public ParamRunner(Class<?> klass) throws InitializationError {
        super(klass);
        loadData(klass, null);
        
    }
    
    /**
     * Try to collect any initialization errors, if any.
     * @param errors
     */
    @Override
    protected void collectInitializationErrors(List<Throwable> errors) {
        super.collectInitializationErrors(errors);
        validateDataPointFields(errors);
    }
    
    private void validateDataPointFields(List<Throwable> errors) {
        Field[] fields= getTestClass().getJavaClass().getDeclaredFields();
        
        for (Field each : fields)
            if (each.getAnnotation(DataPoint.class) != null && !Modifier.isStatic(each.getModifiers()))
                errors.add(new Error("DataPoint field " + each.getName() + " must be static"));
    }
    
    @Override
    protected void validateConstructor(List<Throwable> errors) {
        validateOnlyOneConstructor(errors);
    }
    
    @Override
    protected void validateTestMethods(List<Throwable> errors) {
        for (FrameworkMethod each : computeTestMethods())
            if(each.getAnnotation(Test.class) != null)
                each.validatePublicVoid(false, errors);
            else
                each.validatePublicVoidNoArg(false, errors);
    }
    
    /**
     * Override the methodBlock to return custom {@link ParamAnchor}
     * @param method the Framework Method
     * @return a compiled {@link Statement} object to be evaluated
     */
    @Override
    public Statement methodBlock(final FrameworkMethod method) {
        return new ParamAnchor(method, getTestClass());
    }
    
    @SuppressWarnings("unchecked")
    protected static void loadData(Class testClass , FrameworkMethod method){
        boolean useTestClass = true;
        if(testClass == null && method == null){
            Assert.fail("The framework should provide either the testClass parameter or the method parameter in order to load the test data.");
        }
        //We give priority to Class Loading and then to method loading
        TestData testData = null;
        if(testClass != null){
            testData = (TestData)testClass.getAnnotation(TestData.class);
        }else{
            testData = method.getAnnotation(TestData.class);
            useTestClass = false;
        }
        if(testData != null){
            String[] dataFiles = testData.filePaths();
            FileType fileType = testData.fileType();
            Loader dataLoader = null;
            if(FileType.CUSTOM.equals(fileType)){
                PARAM_LOG.info("User specified to use custom Loader. Trying to get the custom loader.");
                CustomLoader customLoader = null;
                if(useTestClass){
                    customLoader = (CustomLoader) testClass.getAnnotation(CustomLoader.class);
                }else{
                    customLoader = method.getAnnotation(CustomLoader.class);
                }
                
                if(customLoader != null){
                    Class loaderClass = customLoader.loader();
                    try {
                        dataLoader = (Loader) loaderClass.newInstance();
                    } catch (Exception e) {
                        throw new RuntimeException("Exception occured while trying to " +
                                "create an instance of custom Loader Class :" + loaderClass , e);
                    }
                }else{
                    Assert.fail("Specified the FileType as CUSTOM but did not specify CustomLoader.class"+ 
                         " annotation. A fileType of CUSTOM requires the CustomLoader " +
                                "annotation to be specified on the method with its 'loader' attribute specifying " +
                                "the Custom Loader Class which implements : Loader interface.");
                }
                 
            }else{
                dataLoader = LoaderFactory.getLoader(fileType);
            }
            
            
            if(dataLoader == null){
                Assert.fail("The framework currently does not support the specified Loader type. " +
                        "You can provide the custom Loader by choosing FileType.CUSTOM in TestData " +
                        "annotation and providing your custom loader using @CustomLoader annotation." );
            }else{
                Map<String, List<Map<String, String>>> data = dataLoader.loadData(dataFiles);
                DataContext.setData(data);
                
            }
        }
    }

    /**
     * 
     * Static inner class to support Statement evaluation.
     *
     */
    public static class ParamAnchor extends Statement {
        
        /**
         * An instance of logger associated with the test framework.
         */
        protected static final Logger LOG = LoggerFactory.getLogger(ParamRunner.ParamAnchor.class);
        
        private int successes= 0;

        /**
         * an instance of {@link FrameworkMethod} identifying the method to be tested.
         */
        private FrameworkMethod fTestMethod;
        
        /**
         * An instance of {@link TestClass} identifying the class under test
         */
        private TestClass fTestClass;
        
        /**
         * A List of {@link Assignments}. Each member in the list
         * corresponds to a single set of test data to be passed to the test method.
         * For eg. If the user has specified the test data in the CSV file as:<br>
         * <br><B>testGetItems,LibraryId,itemType,searchText</B>
         *   <br>,4,journal,batman
         *   <br>,1,ebook,potter
         *   <br>
         *   where:
         *    <li>testGetItems is the name of the method</li>
         *    <li>LibraryId,itemType,searchText are the names of the parameters that the test method expects</li>
         *    and
         *    <li>,4,journal,batman</li>
         *    <li>,1,ebook,potter</li>
         *    are the actual test data
         *    <br>
         *    then this list will consists of TWO {@link Assignments} instances with values:
         *    <li>[[{LibraryId=4, itemType=journal, searchText=batman}]]</li>
         *    AND
         *    <li>[[{LibraryId=1, itemType=ebook, searchText=potter}]]
         *    
         */
        private List<Assignments> listOfAssignments;

        /**
         * List of Invalid parameters
         */
        private List<AssumptionViolatedException> fInvalidParameters= new ArrayList<AssumptionViolatedException>();

        /**
         * 
         * Construct a new ParamAnchor.
         * The constructor performs the following operations:<br>
         * <li> It sets the class variables method , testClass and initializes the instance of {@link #listOfAssignments}</li>
         * <li> It searches for {@link TestData} annotation and if it finds one, 
         * it tries to get the right {@link Loader} from the {@link LoaderFactory}.
         * If the {@link Loader} is not found, the test fails.
         * If the Loader is found, it loads the data and makes it available to the entier test Thread using {@link DataContext}
         * 
         * If the annotation {@link TestData} is not present, 
         * then the test assumes that the user wants to use {@link ParametersSuppliedBy} annotation and does nothing.
         * @param method the method to run the test on
         * @param testClass an instance of {@link TestClass} 
         */
        public ParamAnchor(FrameworkMethod method, TestClass testClass) {
            fTestMethod= method;
            fTestClass= testClass;
            listOfAssignments = new ArrayList<Assignments>();
            loadData(null , method);
//            if(testData != null){
//                String[] dataFiles = testData.filePaths();
//                FileType fileType = testData.fileType();
//                Loader dataLoader = null;
//                if(FileType.CUSTOM.equals(fileType)){
//                    LOG.info("User specified to use custom Loader. Trying to get the custom loader.");
//                    CustomLoader customLoader = fTestMethod.getAnnotation(CustomLoader.class);
//                    if(customLoader != null){
//                        Class loaderClass = customLoader.loader();
//                        try {
//                            dataLoader = (Loader) loaderClass.newInstance();
//                        } catch (Exception e) {
//                            throw new RuntimeException("Exception occured while trying to " +
//                                    "create an instance of custom Loader Class :" + loaderClass , e);
//                        }
//                    }else{
//                        Assert.fail("Specified the FileType as CUSTOM but did not specify CustomLoader.class"+ 
//                             " annotation. A fileType of CUSTOM requires the CustomLoader " +
//                            		"annotation to be specified on the method with its 'loader' attribute specifying " +
//                            		"the Custom Loader Class which implements : Loader interface.");
//                    }
//                     
//                }else{
//                    dataLoader = LoaderFactory.getLoader(fileType);
//                }
//                
//                
//                if(dataLoader == null){
//                    Assert.fail("The framework currently does not support the specified Loader type. " +
//                    		"You can provide the custom Loader by choosing FileType.CUSTOM in TestData " +
//                    		"annotation and providing your custom loader using @CustomLoader annotation." );
//                }else{
//                    Map<String, List<Map<String, String>>> data = dataLoader.loadData(dataFiles);
//                    DataContext.setData(data);
//                    
//                }
//            }else{
//                LOG.debug("Annotation TestData is not specified on the method : " + method.getName() + 
//                    " . Assuming that the user wants to use ParametersSuppliedBy annotation. ");
//            }
            DataContext.setMethodName(method.getName());
        }

        private TestClass getTestClass() {
            return fTestClass;
        }

        @Override
        public void evaluate() throws Throwable {
            runWithAssignment(Assignments.allUnassigned(
                    fTestMethod.getMethod(), getTestClass()));

            if (successes == 0)
                Assert
                        .fail("Never found parameters that satisfied method assumptions.  Violated assumptions: "
                                + fInvalidParameters);
        }

        /**
         * This method encapsulates the actual change in behavior from the traditional JUnit Theories 
         * way of populating and supplying the test data to the test method.
         * This method creates a list of {@link Assignments} identified by {@link #listOfAssignments}
         * and then calls {@link #runWithCompleteAssignment(Assignments)} for each {@link Assignments} element in the {@link #listOfAssignments}
         * @param parameterAssignment an instance of {@link Assignments} identifying the parameters that needs to be supplied test data
         * @throws Throwable if any exception occurs.
         */
        protected void runWithAssignment(Assignments parameterAssignment)
                throws Throwable {
            while (!parameterAssignment.isComplete()) {
                List<PotentialAssignment> potentialAssignments = parameterAssignment.potentialsForNextUnassigned();
                boolean isFirstSetOfArguments = listOfAssignments.isEmpty();
                for(int i = 0 ; i < potentialAssignments.size() ; i++){
                    if(isFirstSetOfArguments){
                        Assignments assignments = Assignments.allUnassigned(fTestMethod.getMethod(), getTestClass());
                        listOfAssignments.add(assignments.assignNext(potentialAssignments.get(i)));
                    }else{
                        Assignments assignments = listOfAssignments.get(i);
                        try {
                            listOfAssignments.set(i, assignments.assignNext(potentialAssignments.get(i)));
                        } catch (IndexOutOfBoundsException e) {
                            listOfAssignments.add(assignments.assignNext(potentialAssignments.get(i)));
                        }
                    }
                    
                }
                parameterAssignment = parameterAssignment.assignNext(null);
            } 
            if(listOfAssignments.isEmpty()){
                LOG.debug("The list of Assignments is null. It normally happens when the user has not supplied any parameters to the test.");
                LOG.debug(" Creating an instance of Assignments object with all its value unassigned.");
                listOfAssignments.add(Assignments.allUnassigned(
                    fTestMethod.getMethod(), getTestClass()));
            }
            for(Assignments assignments : listOfAssignments){
                runWithCompleteAssignment(assignments);
            }
        }


        protected void runWithCompleteAssignment(final Assignments complete)
                throws InstantiationException, IllegalAccessException,
                InvocationTargetException, NoSuchMethodException, Throwable {
            new BlockJUnit4ClassRunner(getTestClass().getJavaClass()) {
                @Override
                protected void collectInitializationErrors(
                        List<Throwable> errors) {
                    // do nothing
                }

                @Override
                public Statement methodBlock(FrameworkMethod method) {
                    final Statement statement= super.methodBlock(method);
                    return new Statement() {
                        @Override
                        public void evaluate() throws Throwable {
                            try {
                                statement.evaluate();
                                handleDataPointSuccess();
                            } catch (AssumptionViolatedException e) {
                                handleAssumptionViolation(e);
                            } catch (Throwable e) {
                                reportParameterizedError(e, complete
                                        .getArgumentStrings(true));
                            }
                        }

                    };
                }

                @Override
                protected Statement methodInvoker(FrameworkMethod method, Object test) {
                    return methodCompletesWithParameters(method, complete, test);
                }

                @Override
                public Object createTest() throws Exception {
                    return getTestClass().getOnlyConstructor().newInstance(
                            complete.getConstructorArguments(true));
                }
            }.methodBlock(fTestMethod).evaluate();
        }

        private Statement methodCompletesWithParameters(
                final FrameworkMethod method, final Assignments complete, final Object freshInstance) {
            return new Statement() {
                @Override
                public void evaluate() throws Throwable {
                    try {
                        final Object[] values= complete.getMethodArguments(
                                true);
                        method.invokeExplosively(freshInstance, values);
                    } catch (CouldNotGenerateValueException e) {
                        // ignore
                    }
                }
            };
        }

        protected void handleAssumptionViolation(AssumptionViolatedException e) {
            fInvalidParameters.add(e);
        }

        protected void reportParameterizedError(Throwable e, Object... params)
                throws Throwable {
            if (params.length == 0)
                throw e;
            throw new ParameterizedAssertionError(e, fTestMethod.getName(),
                    params);
        }


        protected void handleDataPointSuccess() {
            successes++;
        }
    }

}
