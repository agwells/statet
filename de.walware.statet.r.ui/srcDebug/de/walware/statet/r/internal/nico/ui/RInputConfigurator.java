/*******************************************************************************
 * Copyright (c) 2006-2008 WalWare/StatET-Project (www.walware.de/goto/statet).
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Stephan Wahlbrink - initial API and implementation
 *******************************************************************************/

package de.walware.statet.r.internal.nico.ui;

import org.eclipse.jface.text.contentassist.ContentAssistant;
import org.eclipse.jface.text.source.ISourceViewer;

import de.walware.statet.base.ui.StatetUIServices;
import de.walware.statet.base.ui.sourceeditors.IEditorAdapter;
import de.walware.statet.base.ui.sourceeditors.PathCompletionProcessor;
import de.walware.statet.nico.ui.console.NIConsolePage;

import de.walware.statet.r.core.rsource.IRDocumentPartitions;
import de.walware.statet.r.internal.ui.RUIPlugin;
import de.walware.statet.r.nico.ui.RConsole;
import de.walware.statet.r.ui.editors.RPathCompletionProcessor;
import de.walware.statet.r.ui.editors.RSourceViewerConfiguration;
import de.walware.statet.r.ui.editors.RSourceViewerConfigurator;


public class RInputConfigurator extends RSourceViewerConfigurator {
	
	
	private class RConsoleConfiguration extends RSourceViewerConfiguration {
		
		public RConsoleConfiguration() {
			super((IEditorAdapter) fPage.getAdapter(IEditorAdapter.class),
					RInputConfigurator.this,
					RInputConfigurator.this.getPreferenceStore(),
					StatetUIServices.getSharedColorManager()
					);
		}
		
		@Override
		protected ContentAssistant createContentAssistant(final ISourceViewer sourceViewer) {
			final ContentAssistant contentAssistant = new ContentAssistant();
			final PathCompletionProcessor resourceProcessor = new RPathCompletionProcessor(fPage);
			contentAssistant.setDocumentPartitioning(IRDocumentPartitions.R_PARTITIONING);
			contentAssistant.setContentAssistProcessor(resourceProcessor, IRDocumentPartitions.R_STRING);
			
			return contentAssistant;
		}
		
	}
	
	
	private NIConsolePage fPage;
	
	
	public RInputConfigurator(final NIConsolePage page) {
		super((RConsole) page.getConsole(), RUIPlugin.getDefault().getEditorPreferenceStore());
		fPage = page;
		setConfiguration(new RConsoleConfiguration());
	}
	
}
