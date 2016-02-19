/*=============================================================================#
 # Copyright (c) 2009-2016 Stephan Wahlbrink (WalWare.de) and others.
 # All rights reserved. This program and the accompanying materials
 # are made available under the terms of the Eclipse Public License v1.0
 # which accompanies this distribution, and is available at
 # http://www.eclipse.org/legal/epl-v10.html
 # 
 # Contributors:
 #     Stephan Wahlbrink - initial API and implementation
 #=============================================================================*/

package de.walware.statet.r.internal.debug.ui.actions;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.core.DebugException;
import org.eclipse.jface.text.AbstractDocument;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.ITypedRegion;
import org.eclipse.jface.text.Region;
import org.eclipse.jface.text.TextUtilities;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.statushandlers.StatusManager;

import de.walware.jcommons.collections.ImList;

import de.walware.ecommons.ltk.AstInfo;
import de.walware.ecommons.ltk.ast.AstSelection;
import de.walware.ecommons.ltk.ast.IAstNode;
import de.walware.ecommons.ltk.core.model.ISourceUnit;
import de.walware.ecommons.ltk.ui.sourceediting.ISourceEditor;
import de.walware.ecommons.workbench.ui.WorkbenchUIUtil;

import de.walware.statet.r.core.model.IRModelInfo;
import de.walware.statet.r.core.model.IRModelManager;
import de.walware.statet.r.core.model.IRSourceUnit;
import de.walware.statet.r.core.model.RElementAccess;
import de.walware.statet.r.core.model.RModel;
import de.walware.statet.r.core.rlang.RTokens;
import de.walware.statet.r.core.rsource.ast.NodeType;
import de.walware.statet.r.core.rsource.ast.RAstNode;
import de.walware.statet.r.core.source.IRDocumentConstants;
import de.walware.statet.r.debug.core.IRStackFrame;
import de.walware.statet.r.internal.debug.ui.RDebugUIPlugin;
import de.walware.statet.r.internal.debug.ui.RDebugUIUtils;
import de.walware.statet.r.nico.AbstractRDbgController;


public class StepIntoSelectionHandler extends AbstractHandler {
	
	
	public static RElementAccess searchAccess(final ISourceEditor editor, final IRegion region) {
		try {
			final IDocument document = editor.getViewer().getDocument();
			final ITypedRegion partition = TextUtilities.getPartition(document,
					editor.getDocumentContentInfo().getPartitioning(), region.getOffset(), false );
			final ISourceUnit su = editor.getSourceUnit();
			if (su instanceof IRSourceUnit && region.getOffset() < document.getLength()
					&& ( (IRDocumentConstants.R_DEFAULT_CONTENT_CONSTRAINT.matches(partition.getType())
							&& !RTokens.isRobustSeparator(document.getChar(region.getOffset()), false) )
						|| partition.getType() == IRDocumentConstants.R_QUOTED_SYMBOL_CONTENT_TYPE
						|| partition.getType() == IRDocumentConstants.R_STRING_CONTENT_TYPE )) {
				
				final IRModelInfo info = (IRModelInfo) su.getModelInfo(RModel.TYPE_ID, IRModelManager.MODEL_FILE, new NullProgressMonitor());
				if (info != null) {
					final AstInfo astInfo = info.getAst();
					final AstSelection selection = AstSelection.search(astInfo.root,
							region.getOffset(), region.getOffset()+region.getLength(),
							AstSelection.MODE_COVERING_SAME_LAST );
					final IAstNode covering = selection.getCovering();
					if (covering instanceof RAstNode) {
						final RAstNode node = (RAstNode) covering;
						RAstNode current = node;
						do {
							final ImList<Object> attachments= current.getAttachments();
							for (final Object attachment : attachments) {
								if (attachment instanceof RElementAccess) {
									final RElementAccess access= (RElementAccess) attachment;
									if (access.isFunctionAccess() && access.isCallAccess()) {
										if (isChild(node, access.getNode())) {
											return access;
										}
										return null;
									}
								}
							}
							current = current.getRParent();
						} while (current != null);
					}
				}
			}
		}
		catch (final BadLocationException e) {
		}
		return null;
	}
	
	private static boolean isChild(RAstNode child, final RAstNode parent) {
		do {
			if (child == parent) {
				return true;
			}
			child = child.getRParent();
		} while (child != null);
		return false;
	}
	
	public static void exec(final AbstractRDbgController controller,
			final AbstractDocument document, final RElementAccess access,
			final IWorkbenchPart part) {
		try {
			final RAstNode nameNode = access.getNameNode();
			String code;
			switch (nameNode.getNodeType()) {
			case STRING_CONST:
			case SYMBOL:
				code = access.getSegmentName();
				if (access.getNode().getNodeType() == NodeType.F_CALL) {
					code = "`" + code + "`"; //$NON-NLS-1$ //$NON-NLS-2$
				}
				else {
					code = "get('" + code + "')"; //$NON-NLS-1$ //$NON-NLS-2$
				}
				break;
			default:
				code = document.get(nameNode.getOffset(), nameNode.getLength());
				break;
			}
			if (code != null && code.length() > 0) {
				final IRStackFrame frame = RDebugUIUtils.getFrame(part, controller.getTool());
				controller.debugStepInto((frame != null) ? frame.getPosition() : -1, code);
			}
		}
		catch (final BadLocationException e) {}
		catch (final CoreException e) {
			StatusManager.getManager().handle(new Status(IStatus.ERROR, RDebugUIPlugin.PLUGIN_ID,
							DebugException.REQUEST_FAILED,
							"An error occurred when executing debug request in the R engine.",
							e ),
					StatusManager.LOG );
		}
	}
	
	
	public StepIntoSelectionHandler() {
	}
	
	
	@Override
	public Object execute(final ExecutionEvent event) throws ExecutionException {
		final IWorkbenchPart activePart = WorkbenchUIUtil.getActivePart(event.getApplicationContext());
		final ISourceEditor editor = (ISourceEditor) activePart.getAdapter(ISourceEditor.class);
		if (editor == null) {
			return null;
		}
		final AbstractRDbgController controller = RDebugUIUtils.getRDbgController(editor);
		if (controller == null) {
			return null;
		}
		final ITextSelection selection = (ITextSelection) editor.getViewer().getSelection();
		final RElementAccess access = searchAccess(editor,
				new Region(selection.getOffset(), selection.getLength()) );
		if (access != null) {
			exec(controller, (AbstractDocument) editor.getViewer().getDocument(), access, activePart);
			return null;
		}
		Display.getCurrent().beep();
		return null;
	}
	
}
