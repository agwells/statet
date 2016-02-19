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

package de.walware.statet.r.internal.ui.editors;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.hyperlink.AbstractHyperlinkDetector;
import org.eclipse.jface.text.hyperlink.IHyperlink;

import de.walware.ecommons.ltk.ui.sourceediting.ISourceEditor;

import de.walware.statet.r.core.model.IRSourceUnit;
import de.walware.statet.r.core.model.RElementAccess;
import de.walware.statet.r.ui.sourceediting.ROpenDeclarationHandler;


public class RElementHyperlinkDetector extends AbstractHyperlinkDetector {
	
	
	public RElementHyperlinkDetector() {
	}
	
	
	@Override
	public IHyperlink[] detectHyperlinks(final ITextViewer textViewer,
			final IRegion region, final boolean canShowMultipleHyperlinks) {
		final ISourceEditor editor= (ISourceEditor) getAdapter(ISourceEditor.class);
		if (editor == null) {
			return null;
		}
		
		final List<IHyperlink> hyperlinks= new ArrayList<>(4);
		final RElementAccess access= ROpenDeclarationHandler.searchAccess(editor, region);
		if (access != null) {
			hyperlinks.add(new OpenRElementHyperlink(editor, (IRSourceUnit) editor.getSourceUnit(), access));
		}
		
		if (!hyperlinks.isEmpty()) {
			return hyperlinks.toArray(new IHyperlink[hyperlinks.size()]);
		}
		return null;
	}
	
}
