/*=============================================================================#
 # Copyright (c) 2006-2016 Stephan Wahlbrink (WalWare.de) and others.
 # All rights reserved. This program and the accompanying materials
 # are made available under the terms of the Eclipse Public License v1.0
 # which accompanies this distribution, and is available at
 # http://www.eclipse.org/legal/epl-v10.html
 # 
 # Contributors:
 #     Stephan Wahlbrink - initial API and implementation
 #=============================================================================*/

package de.walware.statet.nico.internal.core;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import org.eclipse.core.runtime.preferences.DefaultScope;
import org.eclipse.core.runtime.preferences.IScopeContext;

import de.walware.ecommons.preferences.PreferencesUtil;
import de.walware.ecommons.preferences.core.Preference;

import de.walware.statet.nico.core.NicoPreferenceNodes;
import de.walware.statet.nico.internal.core.preferences.HistoryPreferences;


public class NicoPreferenceInitializer extends AbstractPreferenceInitializer {
	
	
	@Override
	public void initializeDefaultPreferences() {
		final IScopeContext scope= DefaultScope.INSTANCE;
		final Map<Preference<?>, Object> map= new HashMap<>();
		
		new HistoryPreferences().addPreferencesToMap(map);
		map.put(NicoPreferenceNodes.KEY_DEFAULT_TIMEOUT, 15000);
		
		PreferencesUtil.setPrefValues(scope, map);
	}
	
}
