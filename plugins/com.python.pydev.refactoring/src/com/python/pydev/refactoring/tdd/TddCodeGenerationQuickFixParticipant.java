/**
 * Copyright (c) 2005-2011 by Appcelerator, Inc. All Rights Reserved.
 * Licensed under the terms of the Eclipse Public License (EPL).
 * Please see the license.txt included with this distribution for details.
 * Any modifications to this file must keep this entire header intact.
 */
package com.python.pydev.refactoring.tdd;

import java.io.File;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.python.pydev.core.FullRepIterable;
import org.python.pydev.core.IDefinition;
import org.python.pydev.core.IPythonNature;
import org.python.pydev.core.MisconfigurationException;
import org.python.pydev.core.bundle.ImageCache;
import org.python.pydev.core.docutils.PySelection;
import org.python.pydev.core.docutils.PySelection.LineStartingScope;
import org.python.pydev.core.docutils.PySelection.TddPossibleMatches;
import org.python.pydev.core.log.Log;
import org.python.pydev.editor.PyEdit;
import org.python.pydev.editor.codecompletion.IPyCompletionProposal;
import org.python.pydev.editor.codecompletion.revisited.CompletionCache;
import org.python.pydev.editor.codecompletion.revisited.CompletionStateFactory;
import org.python.pydev.editor.codecompletion.revisited.visitors.AssignDefinition;
import org.python.pydev.editor.codecompletion.revisited.visitors.Definition;
import org.python.pydev.editor.model.ItemPointer;
import org.python.pydev.editor.refactoring.AbstractPyRefactoring;
import org.python.pydev.editor.refactoring.IPyRefactoring;
import org.python.pydev.editor.refactoring.RefactoringRequest;
import org.python.pydev.parser.jython.ast.ClassDef;
import org.python.pydev.parser.visitors.NodeUtils;
import org.python.pydev.parser.visitors.scope.ASTEntry;
import org.python.pydev.parser.visitors.scope.EasyASTIteratorVisitor;

import com.python.pydev.analysis.ctrl_1.AbstractAnalysisMarkersParticipants;
import com.python.pydev.refactoring.refactorer.AstEntryRefactorerRequestConstants;

public class TddCodeGenerationQuickFixParticipant extends AbstractAnalysisMarkersParticipants{


    private TddQuickFixParticipant tddQuickFixParticipant;
    
    protected void fillParticipants() {
        tddQuickFixParticipant = new TddQuickFixParticipant();
        participants.add(tddQuickFixParticipant);
    }
    
    
    public List<ICompletionProposal> getProps(PySelection ps, ImageCache imageCache, File f, IPythonNature nature, PyEdit edit, int offset) throws BadLocationException {
        List<ICompletionProposal> ret = super.getProps(ps, imageCache, f, nature, edit, offset);
        
        
        //Additional option: Generate markers for 'self.' accesses
        int lineOfOffset = ps.getLineOfOffset(offset);
        String lineContents = ps.getLine(lineOfOffset);
        
        //Additional option: Generate methods for function calls
        List<TddPossibleMatches> callsAtLine = ps.getTddPossibleMatchesAtLine();
        if (callsAtLine.size() > 0) {
            //Make sure we don't check the same thing twice.
            Map<String, TddPossibleMatches> callsToCheck = new HashMap<String, TddPossibleMatches>();
            for(TddPossibleMatches call:callsAtLine){
                String callString = call.initialPart+call.secondPart;
                callsToCheck.put(callString, call);
            }
            
            for(Map.Entry<String, TddPossibleMatches> entry:callsToCheck.entrySet()){
                //we have at least something as SomeClass(a=2,c=3) or self.bar or self.foo.bar() or just foo.bar, etc.
                IPyRefactoring pyRefactoring = AbstractPyRefactoring.getPyRefactoring();
                try {
                    TddPossibleMatches possibleMatch = entry.getValue();
                    String full = possibleMatch.full;
                    String callWithoutParens = entry.getKey();
                    int indexOf = lineContents.indexOf(full);
                    if(indexOf < 0){
                        Log.log("Did not expect index < 0.");
                        continue;
                    }
                    PySelection callPs = new PySelection(
                            ps.getDoc(), ps.getLineOffset()+indexOf+callWithoutParens.length());
                    
                    RefactoringRequest request = new RefactoringRequest(edit, callPs);
                    //Don't look in additional info.
                    request.setAdditionalInfo(AstEntryRefactorerRequestConstants.FIND_DEFINITION_IN_ADDITIONAL_INFO, false);
                    ItemPointer[] pointers = pyRefactoring.findDefinition(request);
                    if(pointers.length == 1){
                        //Ok, we found whatever was there, so, we don't need to create anything (except maybe do
                        //the __init__).
                        checkInitCreation(edit, callPs, pointers, ret);
                        
                    }else if(pointers.length == 0){
                        checkMethodCreationAtClass(edit, pyRefactoring, callWithoutParens, callPs, ret, lineContents);
                    }
                } catch (Exception e) {
                    Log.log(e);
                }
            }
        }

        return ret;
    }


    private boolean checkMethodCreationAtClass(PyEdit edit, IPyRefactoring pyRefactoring, String callWithoutParens,
            PySelection callPs, List<ICompletionProposal> ret, String lineContents) throws MisconfigurationException, Exception {
        RefactoringRequest request;
        ItemPointer[] pointers;
        //Ok, no definition found for the full string, so, check if we have a dot there and check
        //if it could be a method in a local variable.
        String[] headAndTail = FullRepIterable.headAndTail(callWithoutParens);
        if(headAndTail[0].length() > 0){

            String methodToCreate = headAndTail[1];
            if (headAndTail[0].equals("self")) {
                //creating something in the current class -- note that if it was self.bar here, we'd treat it as regular
                //(i.e.: no special support for self)
                PyCreateMethod pyCreateMethod = new PyCreateMethod();
                pyCreateMethod.setCreateAs(PyCreateMethod.BOUND_METHOD);

                int firstCharPosition = PySelection.getFirstCharPosition(lineContents);
                LineStartingScope scopeStart = callPs.getPreviousLineThatStartsScope(PySelection.CLASS_TOKEN, false, firstCharPosition);
                String classNameInLine = null;
                if (scopeStart != null) {
                    String startingScopeLineContents = callPs.getLine(scopeStart.iLineStartingScope);
                    classNameInLine = PySelection.getClassNameInLine(startingScopeLineContents);
                    if (classNameInLine != null && classNameInLine.length() > 0) {
                        pyCreateMethod.setCreateInClass(classNameInLine);

                        addCreateMethodOption(callPs, edit, ret, methodToCreate, callPs.getParametersAfterCall(callPs.getAbsoluteCursorOffset()), pyCreateMethod, classNameInLine);
                    }
                }
                return true;
            }

            int absoluteCursorOffset = callPs.getAbsoluteCursorOffset();
            absoluteCursorOffset = absoluteCursorOffset - (1+methodToCreate.length()); //+1 for the dot removed too.
            PySelection newSelection = new PySelection(callPs.getDoc(), absoluteCursorOffset);
            request = new RefactoringRequest(edit, newSelection);
            //Don't look in additional info.
            request.setAdditionalInfo(AstEntryRefactorerRequestConstants.FIND_DEFINITION_IN_ADDITIONAL_INFO, false);
            pointers = pyRefactoring.findDefinition(request);
            if(pointers.length == 1){
                for (ItemPointer pointer : pointers) {
                    Definition definition = pointer.definition;
                    
                    if(definition instanceof AssignDefinition){
                        AssignDefinition assignDef = (AssignDefinition) definition;
                        
                        //if the value is currently None, it will be set later on
                        if(assignDef.value.equals("None")){
                            continue;
                        }
                        IPythonNature nature = edit.getPythonNature(); 
                        
                        //ok, go to the definition of whatever is set
                        IDefinition[] definitions2 = assignDef.module.findDefinition(
                                CompletionStateFactory.getEmptyCompletionState(assignDef.value, nature, new CompletionCache()), 
                                assignDef.line, assignDef.col, nature);
                        
                        if(definitions2.length == 1){
                            definition = (Definition) definitions2[0];
                        }
                    }

                    
                    if(definition != null && definition.ast instanceof ClassDef){
                        ClassDef d = (ClassDef) definition.ast;
                        
                        //Give the user a chance to create the method we didn't find.
                        PyCreateMethod pyCreateMethod = new PyCreateMethod();
                        pyCreateMethod.setCreateAs(PyCreateMethod.BOUND_METHOD);
                        String className = NodeUtils.getRepresentationString(d);
                        pyCreateMethod.setCreateInClass(className);
                        
                        List<String> parametersAfterCall = callPs.getParametersAfterCall(callPs.getAbsoluteCursorOffset());
                        String displayString = "Create "+methodToCreate+" method at "+className+" ("+definition.module.getName()+")";
                        TddRefactorCompletionInModule completion = new TddRefactorCompletionInModule(
                                methodToCreate, 
                                tddQuickFixParticipant.imageMethod, 
                                displayString, 
                                null, 
                                displayString, 
                                IPyCompletionProposal.PRIORITY_CREATE, 
                                edit,
                                definition.module.getFile(),
                                parametersAfterCall,
                                pyCreateMethod,
                                newSelection
                        );
                        completion.locationStrategy = PyCreateAction.LOCATION_STRATEGY_END;
                        ret.add(completion);
                        return true;
                    }
                }
            }
        }
        return false;
    }
    
    private void addCreateMethodOption(PySelection ps, PyEdit edit, List<ICompletionProposal> props, String markerContents,
            List<String> parametersAfterCall, PyCreateMethod pyCreateMethod, String classNameInLine) {
        TddRefactorCompletion tddRefactorCompletion = new TddRefactorCompletion(
                markerContents, 
                tddQuickFixParticipant.imageMethod, 
                "Create "+markerContents+" method at "+classNameInLine, 
                null, 
                null, 
                IPyCompletionProposal.PRIORITY_CREATE, 
                edit,
                PyCreateClass.LOCATION_STRATEGY_BEFORE_CURRENT,
                parametersAfterCall,
                pyCreateMethod,
                ps
        );
        props.add(tddRefactorCompletion);
    }


    private boolean checkInitCreation(PyEdit edit, PySelection callPs, ItemPointer[] pointers, List<ICompletionProposal> ret) {
        for (ItemPointer pointer : pointers) {
            Definition definition = pointer.definition;
            if(definition != null && definition.ast instanceof ClassDef){
                ClassDef d = (ClassDef) definition.ast;
                EasyASTIteratorVisitor visitor = EasyASTIteratorVisitor.create(d);
                
                boolean foundInit = false;
                for(Iterator<ASTEntry> it = visitor.getMethodsIterator();it.hasNext();){
                    ASTEntry next = it.next();
                    if(next.node != null){
                        String rep = NodeUtils.getRepresentationString(next.node);
                        if("__init__".equals(rep)){
                            foundInit = true;
                            break;
                        }
                    }
                }
                
                if(!foundInit){
                    //Give the user a chance to create the __init__.
                    PyCreateMethod pyCreateMethod = new PyCreateMethod();
                    pyCreateMethod.setCreateAs(PyCreateMethod.BOUND_METHOD);
                    String className = NodeUtils.getRepresentationString(d);
                    pyCreateMethod.setCreateInClass(className);
                    
                    
                    List<String> parametersAfterCall = callPs.getParametersAfterCall(callPs.getAbsoluteCursorOffset());
                    String displayString = "Create "+className+" __init__ ("+definition.module.getName()+")";
                    TddRefactorCompletionInModule completion = new TddRefactorCompletionInModule(
                            "__init__", 
                            tddQuickFixParticipant.imageMethod, 
                            displayString, 
                            null, 
                            displayString, 
                            IPyCompletionProposal.PRIORITY_CREATE, 
                            edit,
                            definition.module.getFile(),
                            parametersAfterCall,
                            pyCreateMethod,
                            callPs
                    );
                    completion.locationStrategy = PyCreateAction.LOCATION_STRATEGY_FIRST_METHOD;
                    ret.add(completion);
                    return true;
                }
            }
        }
        return false;
    }


}
