/*=============================================================================#
 # Copyright (c) 2010-2016 Stephan Wahlbrink (WalWare.de) and others.
 # All rights reserved. This program and the accompanying materials
 # are made available under the terms of the Eclipse Public License v1.0
 # which accompanies this distribution, and is available at
 # http://www.eclipse.org/legal/epl-v10.html
 # 
 # Contributors:
 #     Stephan Wahlbrink - initial API and implementation
 #=============================================================================*/

package de.walware.statet.r.internal.ui.dataeditor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import com.ibm.icu.util.TimeZone;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.statushandlers.StatusManager;

import de.walware.jcommons.collections.ImCollections;
import de.walware.jcommons.collections.ImList;

import de.walware.ecommons.FastList;
import de.walware.ecommons.ts.ISystemRunnable;
import de.walware.ecommons.ts.ITool;
import de.walware.ecommons.ts.IToolRunnable;
import de.walware.ecommons.ts.IToolService;
import de.walware.ecommons.ui.util.UIAccess;
import de.walware.ecommons.waltable.coordinate.PositionId;
import de.walware.ecommons.waltable.data.ControlData;
import de.walware.ecommons.waltable.data.IDataProvider;
import de.walware.ecommons.waltable.sort.ISortModel;
import de.walware.ecommons.waltable.sort.SortDirection;

import de.walware.rj.data.RCharacterStore;
import de.walware.rj.data.RDataUtil;
import de.walware.rj.data.RFactorStore;
import de.walware.rj.data.RIntegerStore;
import de.walware.rj.data.RLanguage;
import de.walware.rj.data.RObject;
import de.walware.rj.data.RObjectFactory;
import de.walware.rj.data.RStore;
import de.walware.rj.data.RVector;
import de.walware.rj.data.UnexpectedRDataException;
import de.walware.rj.data.defaultImpl.RFactorDataStruct;
import de.walware.rj.data.defaultImpl.RObjectFactoryImpl;
import de.walware.rj.eclient.FQRObjectRef;
import de.walware.rj.eclient.IRToolService;
import de.walware.rj.services.FunctionCall;
import de.walware.rj.services.IFQRObjectRef;
import de.walware.rj.services.RService;
import de.walware.rj.services.utils.dataaccess.AbstractRDataAdapter;
import de.walware.rj.services.utils.dataaccess.LazyRStore;
import de.walware.rj.services.utils.dataaccess.LazyRStore.Fragment;
import de.walware.rj.services.utils.dataaccess.RDataAssignment;

import de.walware.statet.r.core.model.RElementName;
import de.walware.statet.r.nico.ICombinedRDataAdapter;
import de.walware.statet.r.ui.RUI;
import de.walware.statet.r.ui.dataeditor.IRDataTableInput;
import de.walware.statet.r.ui.dataeditor.IRDataTableVariable;
import de.walware.statet.r.ui.dataeditor.RDataTableColumn;


public abstract class AbstractRDataProvider<T extends RObject> implements IDataProvider {
	
	
	public static final ControlData LOADING= new ControlData(ControlData.ASYNC, "loading...");
	public static final ControlData ERROR= new ControlData(ControlData.ERROR, "ERROR");
	public static final ControlData NA= new ControlData(ControlData.NA, "NA"); //$NON-NLS-1$
	public static final ControlData DUMMY= new ControlData(0, ""); //$NON-NLS-1$
	
	protected static final RElementName BASE_NAME= RElementName.create(RElementName.MAIN_DEFAULT, "x"); //$NON-NLS-1$
	
	
	static final void checkCancel(final Exception e) throws CoreException {
		if (e instanceof CoreException
				&& ((CoreException) e).getStatus().getSeverity() == IStatus.CANCEL) {
			throw (CoreException) e;
		}
	}
	
	static final void cleanTmp(final String name, final IRToolService r, final IProgressMonitor monitor) throws CoreException {
		final FunctionCall call= r.createFunctionCall(RJTmp.REMOVE); 
		call.addChar(RJTmp.NAME_PAR, name);
		call.evalVoid(monitor);
	}
	
	
	public static final class SortColumn {
		
		
		private final long id;
		
		public final boolean decreasing;
		
		
		public SortColumn(final long columnId, final boolean decreasing) {
			this.id= columnId;
			this.decreasing= decreasing;
		}
		
		
		public long getId() {
			return this.id;
		}
		
		public long getIdx() {
			return (this.id & PositionId.NUM_MASK);
		}
		
		
		@Override
		public int hashCode() {
			final int h= (int) (this.id ^ (this.id >>> 32));
			return (this.decreasing) ? (-h) : h;
		}
		
		@Override
		public boolean equals(final Object obj) {
			if (!(obj instanceof SortColumn)) {
				return false;
			}
			final SortColumn other= (SortColumn) obj;
			return (this.id == other.id && this.decreasing == other.decreasing);
		}
		
	}
	
	public static interface IDataProviderListener {
		
		
		static final int ERROR_STRUCT_CHANGED= 1;
		
		
		void onInputInitialized(boolean structChanged);
		
		void onInputFailed(int error);
		
		void onRowCountChanged();
		
		void onRowsChanged(long begin, long end);
		
	}
	
	private static final long DEFAULT_WAIT= 25_000000;
	private static final long CANCEL_WAIT= 200_000000;
	private static final long BLOCKING_WAIT= 300_000000;
	
	private class MainLock extends Lock implements LazyRStore.Updater {
		
		
		private final List<Fragment> waitingFragments= new ArrayList<>();
		
		
		@Override
		public void scheduleUpdate(final LazyRStore store,
				final RDataAssignment assignment, final Fragment fragment,
				final int flags, final IProgressMonitor monitor) {
			long waitNanos;
			if (!this.scheduled) {
				this.scheduled= true;
				AbstractRDataProvider.this.schedule(AbstractRDataProvider.this.updateRunnable);
				
				waitNanos= ((flags & FORCE_SYNC) != 0) ? 0 : DEFAULT_WAIT;
			}
			else {
				waitNanos= ((flags & FORCE_SYNC) != 0) ? 0 : -1;
			}
			
			if (waitNanos >= 0 && fragment != null) {
				this.waitingFragments.add(fragment);
				this.worker.signalAll();
//				long blockingMonitor= (monitor instanceof IProgressMonitorWithBlocking) ? 0 : -1;
				try {
					if (waitNanos == 0) {
						do {
							// wait required to check cancel state
							waitNanos= this.requestor.awaitNanos(CANCEL_WAIT);
							
//							if (blockingMonitor >= 0 && blockingMonitor < BLOCKING_WAIT) {
//								blockingMonitor+= CANCEL_WAIT - waitNanos;
//								if (blockingMonitor >= BLOCKING_WAIT) {
//									((IProgressMonitorWithBlocking) monitor).setBlocked(
//											new Status(IStatus.INFO, RUI.PLUGIN_ID, "Waiting for R."));
//								}
//							}
						}
						while (this.waitingFragments.contains(fragment)
								&& this.state == 0
								&& (monitor == null || !monitor.isCanceled()) );
					}
					else {
						do {
							waitNanos= this.requestor.awaitNanos(waitNanos);
						}
						while (this.waitingFragments.contains(fragment)
								&& this.state == 0
								&& (monitor == null || !monitor.isCanceled())
								&& (waitNanos > 0) );
					}
				}
				catch (final InterruptedException e) {}
				finally {
					this.waitingFragments.remove(fragment);
					
//					if (blockingMonitor >= BLOCKING_WAIT) {
//						((IProgressMonitorWithBlocking) monitor).clearBlocked();
//					}
				}
			}
		}
		
		void notify(final Object obj) {
			if (this.waitingFragments.remove(obj)) {
				this.requestor.signalAll();
			}
		}
		
		@Override
		void clear() {
			this.waitingFragments.clear();
			super.clear();
		}
		
	}
	
	protected class ColumnDataProvider implements IDataProvider {
		
		
		public ColumnDataProvider() {
		}
		
		
		@Override
		public long getColumnCount() {
			return AbstractRDataProvider.this.getColumnCount();
		}
		
		@Override
		public long getRowCount() {
			return 1;
		}
		
		@Override
		public Object getDataValue(final long columnIndex, final long rowIndex,
				final int flags, final IProgressMonitor monitor) {
			try {
				final LazyRStore.Fragment<T> fragment= AbstractRDataProvider.this.fragmentsLock.getFragment(
						AbstractRDataProvider.this.dataStore, 0, columnIndex, flags, monitor);
				if (fragment != null) {
					return getColumnName(fragment, columnIndex);
				}
				else {
					return handleMissingData(flags);
				}
			}
			catch (final LoadDataException e) {
				return handleLoadDataException(e, flags);
			}
		}
		
		@Override
		public void setDataValue(final long columnIndex, final long rowIndex, final Object newValue) {
			throw new UnsupportedOperationException();
		}
		
	}
	
	protected class RowDataProvider implements IDataProvider {
		
		
		private final LazyRStore<RVector<?>> rowNamesStore= new LazyRStore<>(AbstractRDataProvider.this.rowCount, 1,
				10, AbstractRDataProvider.this.fragmentsLock);
		
		
		public RowDataProvider() {
		}
		
		
		@Override
		public long getColumnCount() {
			return 1;
		}
		
		@Override
		public long getRowCount() {
			return AbstractRDataProvider.this.getRowCount();
		}
		
		@Override
		public Object getDataValue(final long columnIndex, final long rowIndex, final int flags, final IProgressMonitor monitor) {
			try {
				final LazyRStore.Fragment<RVector<?>> fragment= AbstractRDataProvider
						.this.fragmentsLock.getFragment(this.rowNamesStore, rowIndex, 0,
								flags, monitor );
				if (fragment != null) {
					final RVector<?> vector= fragment.getRObject();
					if (vector != null) {
						RStore<?> names= vector.getNames();
						if (names == null) {
							names= vector.getData();
						}
						return names.get(rowIndex - fragment.getRowBeginIdx());
					}
					return Long.toString(rowIndex + 1);
				}
				else {
					return handleMissingData(flags);
				}
			}
			catch (final LoadDataException e) {
				return handleLoadDataException(e, flags);
			}
		}
		
		public long getRowIdx(final long rowIndex) {
			try {
				final LazyRStore.Fragment<RVector<?>> fragment= AbstractRDataProvider
						.this.fragmentsLock.getFragment(this.rowNamesStore, rowIndex, 0, 0, null);
				if (fragment != null) {
					final RVector<?> vector= fragment.getRObject();
					if (vector != null) {
						final RStore<?> idxs= vector.getData();
						return ((idxs.getStoreType() == RStore.INTEGER) ?
										(long) idxs.getInt(rowIndex - fragment.getRowBeginIdx()) :
										(long) idxs.getNum(rowIndex - fragment.getRowBeginIdx()) )
								- 1;
					}
					return rowIndex;
				}
				else {
					return -1;
				}
			}
			catch (final LoadDataException e) {
				return -2;
			}
		}
		
		@Override
		public void setDataValue(final long columnIndex, final long rowIndex, final Object newValue) {
			throw new UnsupportedOperationException();
		}
		
	}
	
	protected class SortModel implements ISortModel {
		
		
		@Override
		public List<Long> getSortedColumnIds() {
			final SortColumn sortColumn= getSortColumn();
			if (sortColumn != null) {
				return Collections.singletonList(sortColumn.id);
			}
			return Collections.<Long>emptyList();
		}
		
		@Override
		public void sort(final long columnId, final SortDirection sortDirection, final boolean accumulate) {
			SortColumn sortColumn;
			switch (sortDirection) {
			case ASC:
				sortColumn= new SortColumn(columnId, false);
				break;
			case DESC:
				sortColumn= new SortColumn(columnId, true);
				break;
			default:
				sortColumn= null;
				break;
			}
			setSortColumn(sortColumn);
		}
		
		@Override
		public int getSortOrder(final long columnId) {
			final SortColumn sortColumn= getSortColumn();
			if (sortColumn != null && sortColumn.id == columnId) {
				return 0;
			}
			return -1;
		}
		
		@Override
		public boolean isSorted(final long columnId) {
			final SortColumn sortColumn= getSortColumn();
			if (sortColumn != null && sortColumn.id == columnId) {
				return true;
			}
			return false;
		}
		
		@Override
		public SortDirection getSortDirection(final long columnId) {
			final SortColumn sortColumn= getSortColumn();
			if (sortColumn != null && sortColumn.id == columnId) {
				return (!sortColumn.decreasing) ? SortDirection.ASC : SortDirection.DESC;
			}
			return SortDirection.NONE;
		}
		
		@Override
		public void clear() {
			setSortColumn(null);
		}
		
	}
	
	
	private final IToolRunnable initRunnable= new ISystemRunnable() {
		
		@Override
		public String getTypeId() {
			return "r/dataeditor/init";
		}
		
		@Override
		public String getLabel() {
			return "Prepare Data Viewer (" + AbstractRDataProvider.this.input.getName() + ")";
		}
		
		@Override
		public boolean isRunnableIn(final ITool tool) {
			return true; // TODO
		}
		
		@Override
		public boolean changed(final int event, final ITool tool) {
			if (event == MOVING_FROM) {
				return false;
			}
			return true;
		}
		
		@Override
		public void run(final IToolService service,
				final IProgressMonitor monitor) throws CoreException {
			runInit((IRToolService) service, monitor);
		}
		
	};
	
	private final IToolRunnable updateRunnable= new ISystemRunnable() {
		
		@Override
		public String getTypeId() {
			return "r/dataeditor/load"; //$NON-NLS-1$
		}
		
		@Override
		public String getLabel() {
			return "Load Data (" + AbstractRDataProvider.this.input.getName() + ")";
		}
		
		@Override
		public boolean isRunnableIn(final ITool tool) {
			return true; // TODO
		}
		
		@Override
		public boolean changed(final int event, final ITool tool) {
			switch (event) {
			case MOVING_FROM:
				return false;
			case REMOVING_FROM:
			case BEING_ABANDONED:
				AbstractRDataProvider.this.fragmentsLock.lock();
				try {
					AbstractRDataProvider.this.fragmentsLock.scheduled= false;
					AbstractRDataProvider.this.fragmentsLock.clear();
				}
				finally {
					AbstractRDataProvider.this.fragmentsLock.unlock();
				}
				break;
			default:
				break;
			}
			return true;
		}
		
		@Override
		public void run(final IToolService service,
				final IProgressMonitor monitor) throws CoreException {
			runUpdate((IRToolService) service, monitor);
		}
		
	};
	
	private final IToolRunnable cleanRunnable= new ISystemRunnable() {
		
		@Override
		public String getTypeId() {
			return "r/dataeditor/clean"; //$NON-NLS-1$
		}
		
		@Override
		public String getLabel() {
			return "Clean Cache (" + AbstractRDataProvider.this.input.getName() + ")";
		}
		
		@Override
		public boolean isRunnableIn(final ITool tool) {
			return true; // TODO
		}
		
		@Override
		public boolean changed(final int event, final ITool tool) {
			switch (event) {
			case MOVING_FROM:
			case REMOVING_FROM:
				return false;
			default:
				break;
			}
			return true;
		}
		
		@Override
		public void run(final IToolService service,
				final IProgressMonitor monitor) throws CoreException {
			runClean((IRToolService) service, monitor);
		}
		
	};
	
	
	private final Display realm;
	
	private final IRDataTableInput input;
	
	private final long columnCount;
	private long fullRowCount;
	private long rowCount;
	
	private final FastList<IDataProviderListener> dataListeners= new FastList<>(IDataProviderListener.class);
	
	private boolean initScheduled;
	private volatile boolean disposeScheduled;
	
	
	private RDataTableContentDescription description;
	
	private final IDataProvider columnDataProvider;
	private final IDataProvider rowDataProvider;
	
	private final IDataProvider columnLabelProvider;
	private final IDataProvider rowLabelProvider;
	
	private final MainLock fragmentsLock= new MainLock();
	
	protected final AbstractRDataAdapter<T, T> adapter;
	private final LazyRStore<T> dataStore;
	
	private final List<Object> activeOperations= new ArrayList<>();
	
	private boolean updateSorting;
	private boolean updateFiltering;
	
	private final StringBuilder rStringBuilder= new StringBuilder(128);
	private String rCacheId; // only in R jobs
	private T rObjectStruct;
	
	private final ISortModel sortModel;
	private SortColumn sortColumn= null;
	private String rCacheSort; // only in R jobs
	
	private String filter;
	private String rCacheFilter;
	
	private boolean updateIdx; // only in R jobs
	private String rCacheIdx; // only in R jobs
	private String rCacheIdxR; // only in R jobs
	
	private final FindManager findManager;
	
	
	protected AbstractRDataProvider(final IRDataTableInput input,
			final AbstractRDataAdapter<T, T> adapter, final T initialRObject) {
		this.realm= UIAccess.getDisplay();
		this.input= input;
		
		this.adapter= adapter;
		this.fullRowCount= this.rowCount= this.adapter.getRowCount(initialRObject);
		this.columnCount= this.adapter.getColumnCount(initialRObject);
		
		final int dataMax;
		if (this.columnCount <= 25) {
			dataMax= 10;
		}
		else if (this.columnCount <= 50) {
			dataMax= 20;
		}
		else {
			dataMax= 25;
		}
		this.dataStore= new LazyRStore<>(this.rowCount, this.columnCount, dataMax, this.fragmentsLock);
		this.findManager= new FindManager(this);
		
		this.columnDataProvider= createColumnDataProvider();
		this.rowDataProvider= createRowDataProvider();
		this.columnLabelProvider= createColumnLabelProvider();
		this.rowLabelProvider= createRowLabelProvider();
		this.sortModel= createSortModel();
	}
	
	
	public final IRDataTableInput getInput() {
		return this.input;
	}
	
	protected final AbstractRDataAdapter<T, T> getAdapter() {
		return this.adapter;
	}
	
	public final T getRObject() {
		return this.rObjectStruct;
	}
	
	final int getLockState() {
		return this.fragmentsLock.state;
	}
	
	public final void beginOperation(final Object o) {
		this.fragmentsLock.lock();
		try {
			this.activeOperations.add(o);
		}
		finally {
			this.fragmentsLock.unlock();
		}
	}
	
	public final void endOperation(final Object o) {
		this.fragmentsLock.lock();
		try {
			if (this.activeOperations.remove(o)
					&& this.activeOperations.isEmpty() ) {
				this.fragmentsLock.notifyWorker();
			}
		}
		finally {
			this.fragmentsLock.unlock();
		}
	}
	
	final void schedule(final IToolRunnable runnable) {
		try {
			final ITool tool= this.input.getTool();
			final IStatus status= tool.getQueue().add(runnable);
			if (status.getSeverity() == IStatus.ERROR && !tool.isTerminated()) {
				throw new CoreException(status);
			}
		}
		catch (final CoreException e) {
			clear(Lock.ERROR_STATE);
			StatusManager.getManager().handle(new Status(IStatus.ERROR, RUI.PLUGIN_ID, -1,
					"An error occurred when scheduling job for data viewer.", e));
		}
	}
	
	private void runInit(final IRToolService r, final IProgressMonitor monitor) throws CoreException {
		if (this.disposeScheduled) {
			synchronized (this.initRunnable) {
				this.initScheduled= false;
			}
			return;
		}
		
		try {
			if (this.rCacheId == null) {
//				r.evalVoid("require(\"rj\", quietly= TRUE)", monitor);
				final FunctionCall call= r.createFunctionCall(RJTmp.CREATE_ID);
				call.addChar(RJTmp.PREFIX_PAR, "viewer"); //$NON-NLS-1$
				this.rCacheId= RDataUtil.checkSingleCharValue(call.evalData(monitor));
			}
		}
		catch (final Exception e) {
			synchronized (this.initRunnable) {
				this.initScheduled= false;
			}
			checkCancel(e);
			clear(Lock.ERROR_STATE);
			StatusManager.getManager().handle(new Status(IStatus.ERROR, RUI.PLUGIN_ID, -1,
					"An error occurred when preparing tmp variables for data viewer.", e));
			
			this.realm.syncExec(new Runnable() {
				@Override
				public void run() {
					for (final IDataProviderListener listener : AbstractRDataProvider.this.dataListeners.toArray()) {
						listener.onInputFailed(0);
					}
				}
			});
			return;
		}
		
		try {
			final RObject rObject= (r instanceof ICombinedRDataAdapter) ?
					((ICombinedRDataAdapter) r).evalCombinedStruct(this.input.getElementName(), 0, 1, monitor) :
					r.evalData(this.input.getFullName(), null, RObjectFactory.F_ONLY_STRUCT, 1, monitor);
			if (this.rObjectStruct == null) {
				this.rObjectStruct= this.adapter.validate(rObject);
			}
			else {
				this.rObjectStruct= this.adapter.validate(rObject, this.rObjectStruct, 0);
			}
		}
		catch (final Exception e) {
			synchronized (this.initRunnable) {
				this.initScheduled= false;
			}
			checkCancel(e);
			clear(Lock.RELOAD_STATE);
			StatusManager.getManager().handle(new Status(IStatus.ERROR, RUI.PLUGIN_ID, -1,
					"An error occurred when initializing structure data for data viewer.", e));
			
			this.realm.syncExec(new Runnable() {
				@Override
				public void run() {
					for (final IDataProviderListener listener : AbstractRDataProvider.this.dataListeners.toArray()) {
						listener.onInputFailed(IDataProviderListener.ERROR_STRUCT_CHANGED);
					}
				}
			});
			return;
		}
		final RDataTableContentDescription description;
		try {
			description= loadDescription(this.input.getElementName(), this.rObjectStruct, r, monitor);
		}
		catch (final Exception e) {
			synchronized (this.initRunnable) {
				this.initScheduled= false;
			}
			checkCancel(e);
			clear(Lock.RELOAD_STATE);
			StatusManager.getManager().handle(new Status(IStatus.ERROR, RUI.PLUGIN_ID, -1,
					"An error occurred when initializing default formats for data viewer.", e));
			return;
		}
		
		this.realm.syncExec(new Runnable() {
			@Override
			public void run() {
				AbstractRDataProvider.this.description= description;
				final long rowCount= AbstractRDataProvider.this.adapter.getRowCount(AbstractRDataProvider.this.rObjectStruct);
				final boolean rowsChanged= (rowCount != getRowCount());
				clear(0, rowCount, rowCount, true, true, true);
				
				synchronized (AbstractRDataProvider.this.initRunnable) {
					AbstractRDataProvider.this.initScheduled= false;
				}
//				if (rowsChanged) {
//					for (final IDataProviderListener listener : dataListeners) {
//						listener.onRowCountChanged();
//					}
//				}
				for (final IDataProviderListener listener : AbstractRDataProvider.this.dataListeners.toArray()) {
					listener.onInputInitialized(rowsChanged);
				}
			}
		});
	}
	
	final String createTmp(final String key) {
		return this.rCacheId + key;
	}
	
	private void runUpdate(final IRToolService r, final IProgressMonitor monitor) throws CoreException {
		int work= -1;
		while (true) {
			try {
				final boolean updateSorting;
				final boolean updateFiltering;
				
				this.fragmentsLock.lock();
				try {
					switch (work) {
					case -1: 
						this.fragmentsLock.scheduled= false;
						break;
					case 0:
						if (!this.activeOperations.isEmpty()) {
							try {
								this.fragmentsLock.worker.await();
							}
							catch (final InterruptedException e) {}
							break;
						}
						else {
							return;
						}
					default:
						break;
					}
					work= 0;
					
					updateSorting= this.updateSorting;
					updateFiltering= this.updateFiltering;
				}
				finally {
					this.fragmentsLock.unlock();
				}
				
				IFQRObjectRef elementRef= null;
				
				if (updateSorting) {
					if (elementRef == null) {
						elementRef= checkElementRef(this.input.getElementRef(), r, monitor);
						this.adapter.check(elementRef, this.rObjectStruct, r, monitor);
					}
					updateSorting(r, monitor);
					work++;
				}
				if (updateFiltering) {
					if (elementRef == null) {
						elementRef= checkElementRef(this.input.getElementRef(), r, monitor);
						this.adapter.check(elementRef, this.rObjectStruct, r, monitor);
					}
					updateFiltering(r, monitor);
					work++;
				}
				if (this.updateIdx) {
					if (elementRef == null) {
						elementRef= checkElementRef(this.input.getElementRef(), r, monitor);
						this.adapter.check(elementRef, this.rObjectStruct, r, monitor);
					}
					updateIdx(r, monitor);
					work++;
				}
				
				if (work == 0
						&& this.rowDataProvider instanceof AbstractRDataProvider<?>.RowDataProvider) {
					final LazyRStore<RVector<?>> namesStore= ((RowDataProvider) this.rowDataProvider).rowNamesStore;
					while (true) {
						final Fragment<RVector<?>> fragment;
						this.fragmentsLock.lock();
						try {
							fragment= namesStore.getNextScheduledFragment();
						}
						finally {
							this.fragmentsLock.unlock();
						}
						if (fragment == null) {
							break;
						}
						
						if (elementRef == null) {
							elementRef= checkElementRef(this.input.getElementRef(), r, monitor);
							this.adapter.check(elementRef, this.rObjectStruct, r, monitor);
						}
						final RVector<?> fragmentObject= this.adapter.loadRowNames(elementRef,
								this.rObjectStruct, fragment, this.rCacheIdx, r, monitor);
						this.fragmentsLock.lock();
						try {
							namesStore.updateFragment(fragment, fragmentObject);
							
							this.fragmentsLock.notify(fragment);
						}
						finally {
							this.fragmentsLock.unlock();
						}
						notifyListener(fragment);
						work++;
					}
				}
				if (work == 0) {
					while (true) {
						final Fragment<T> fragment;
						this.fragmentsLock.lock();
						try {
							fragment= this.dataStore.getNextScheduledFragment();
						}
						finally {
							this.fragmentsLock.unlock();
						}
						if (fragment == null) {
							break;
						}
						
						if (elementRef == null) {
							elementRef= checkElementRef(this.input.getElementRef(), r, monitor);
							this.adapter.check(elementRef, this.rObjectStruct, r, monitor);
						}
						final T fragmentObject= this.adapter.loadData(elementRef,
								this.rObjectStruct, fragment, this.rCacheIdx, r, monitor);
						
						this.fragmentsLock.lock();
						try {
							this.dataStore.updateFragment(fragment, fragmentObject);
							
							this.fragmentsLock.notify(fragment);
						}
						finally {
							this.fragmentsLock.unlock();
						}
						notifyListener(fragment);
						work++;
					}
				}
			}
			catch (final Exception e) {
				checkCancel(e);
				clear(Lock.RELOAD_STATE);
				StatusManager.getManager().handle(new Status(IStatus.ERROR, RUI.PLUGIN_ID, -1,
						NLS.bind("An error occurred when loading data of ''{0}'' for data viewer.", this.input.getFullName()), e));
				return;
			}
		}
	}
	
	private IFQRObjectRef checkElementRef(final IFQRObjectRef elementRef,
			final RService r, final IProgressMonitor monitor) throws UnexpectedRDataException, CoreException {
		RObject env= elementRef.getEnv();
		switch (env.getRObjectType()) {
		case RObject.TYPE_REFERENCE:
			return elementRef;
		case RObject.TYPE_LANGUAGE:
			env= RDataUtil.checkRReference(
					r.evalData(((RLanguage) env).getSource(),
							null, 0, RService.DEPTH_REFERENCE, monitor ),
					RObject.TYPE_ENV );
			return new FQRObjectRef((ITool) elementRef.getRHandle(), env, elementRef.getName());
		default:
			throw new UnexpectedRDataException(
					"Unexpected R object type: " + RDataUtil.getObjectTypeName(env.getRObjectType()) );
		}
	}
	
	private void updateSorting(
			final IRToolService r, final IProgressMonitor monitor) throws UnexpectedRDataException, CoreException {
		cleanSorting(r, monitor);
		
		final SortColumn sortColumn;
		this.fragmentsLock.lock();
		try {
			sortColumn= this.sortColumn;
			this.updateSorting= false;
		}
		finally {
			this.fragmentsLock.unlock();
		}
		
		if (sortColumn != null) {
			if (this.rCacheSort == null) {
				this.rCacheSort= this.rCacheId + ".order"; //$NON-NLS-1$
			}
			final FunctionCall call= r.createFunctionCall(RJTmp.SET); 
			call.addChar(RJTmp.NAME_PAR, this.rCacheSort);
			final StringBuilder cmd= getRCmdStringBuilder();
			appendOrderCmd(cmd, sortColumn);
			call.add(RJTmp.VALUE_PAR, cmd.toString()); 
			call.evalVoid(monitor);
		}
	}
	
	private void updateFiltering(
			final IRToolService r, final IProgressMonitor monitor) throws UnexpectedRDataException, CoreException {
		cleanFiltering(r, monitor);
		
		String filter;
		this.fragmentsLock.lock();
		try {
			filter= this.filter;
			this.updateFiltering= false;
		}
		finally {
			this.fragmentsLock.unlock();
		}
		
		final long filteredRowCount;
		if (filter == null) {
			filteredRowCount= getFullRowCount();
		}
		else {
			if (this.rCacheFilter == null) {
				this.rCacheFilter= this.rCacheId + ".include"; //$NON-NLS-1$
			}
			{	final FunctionCall call= r.createFunctionCall(RJTmp.SET); 
				call.addChar(RJTmp.NAME_PAR, this.rCacheFilter);
				call.add(RJTmp.VALUE_PAR, filter); 
				call.evalVoid(monitor);
			}
			{	final FunctionCall call= r.createFunctionCall(RJTmp.GET_FILTERED_COUNT);
				call.addChar(RJTmp.FILTER_PAR, this.rCacheFilter);
				filteredRowCount= RDataUtil.checkSingleIntValue(call.evalData(monitor));
			}
		}
		this.realm.syncExec(new Runnable() {
			@Override
			public void run() {
				clear(0, filteredRowCount, getFullRowCount(), false, false, false);
				for (final IDataProviderListener listener : AbstractRDataProvider.this.dataListeners.toArray()) {
					listener.onRowCountChanged();
				}
			}
		});
	}
	
	private void updateIdx(
			final IRToolService r, final IProgressMonitor monitor) throws UnexpectedRDataException, CoreException {
		cleanIdx(r, monitor);
		if (this.rCacheSort != null || this.rCacheFilter != null) {
			if (this.rCacheIdx == null) {
				this.rCacheIdx= this.rCacheId + ".idx"; //$NON-NLS-1$
			}
			if (this.rCacheFilter == null) { // fRCacheSort != null
				final FunctionCall call= r.createFunctionCall(RJTmp.SET);
				call.addChar(RJTmp.NAME_PAR, this.rCacheIdx);
				call.add(RJTmp.VALUE_PAR, RJTmp.ENV+'$'+ this.rCacheSort);
				call.evalVoid(monitor);
			}
			else if (this.rCacheSort == null) { // fRCacheFilter != null
				final FunctionCall call= r.createFunctionCall(RJTmp.SET_WHICH_INDEX);
				call.addChar(RJTmp.NAME_PAR, this.rCacheIdx);
				call.addChar(RJTmp.FILTER_PAR, this.rCacheFilter);
				call.evalVoid(monitor);
			}
			else { // fRCacheSort != null && fRCacheFilter != null
				final FunctionCall call= r.createFunctionCall(RJTmp.SET_FILTERED_INDEX);
				call.addChar(RJTmp.NAME_PAR, this.rCacheIdx);
				call.addChar(RJTmp.FILTER_PAR, this.rCacheFilter);
				call.addChar(RJTmp.INDEX_PAR, this.rCacheSort);
				call.evalVoid(monitor);
			}
		}
		this.updateIdx= false;
	}
	
	String checkFilter() {
		return this.rCacheFilter;
	}
	
	String checkRevIndex(
			final IRToolService r, final IProgressMonitor monitor) throws CoreException {
		if (this.rCacheIdx != null) {
			if (this.rCacheIdxR == null) {
				final String name= this.rCacheIdx + ".r"; //$NON-NLS-1$
				try {
					final FunctionCall call= r.createFunctionCall(RJTmp.SET_REVERSE_INDEX);
					call.addChar(RJTmp.NAME_PAR, name);
					call.addChar(RJTmp.INDEX_PAR, this.rCacheIdx);
					call.addNum(RJTmp.LEN_PAR, getFullRowCount());
					call.evalVoid(monitor);
					return this.rCacheIdxR= name;
				}
				finally {
					if (this.rCacheIdxR == null) {
						cleanTmp(name, r, monitor);
					}
				}
			}
			return this.rCacheIdxR;
		}
		else {
			return null;
		}
	}
	
	protected abstract RDataTableContentDescription loadDescription(RElementName name,
			T struct, IRToolService r,
			IProgressMonitor monitor) throws CoreException, UnexpectedRDataException;
	
	
	protected RDataTableColumn createColumn(final RStore store, final String expression,
			final RElementName elementName, final long columnIndex, final String columnName,
			final IRToolService r, final IProgressMonitor monitor) throws CoreException, UnexpectedRDataException {
		
		final ImList<String> classNames;
		
		RObject rObject;
		{	final FunctionCall call= r.createFunctionCall("class"); //$NON-NLS-1$
			call.add(expression);
			rObject= call.evalData(monitor);
			final RVector<RCharacterStore> names= RDataUtil.checkRCharVector(rObject);
			classNames= ImCollections.newList(names.getData().toArray());
		}
		RDataTableColumn column;
		final RDataFormatter format= new RDataFormatter();
		switch (store.getStoreType()) {
		case RStore.LOGICAL:
			format.setAutoWidth(5);
			column= new RDataTableColumn(columnIndex, columnName, expression, elementName,
					IRDataTableVariable.LOGI, store, classNames, format);
			break;
		case RStore.NUMERIC:
			if (checkDateFormat(expression, classNames, format, r, monitor)) {
				column= new RDataTableColumn(columnIndex, columnName, expression, elementName,
						IRDataTableVariable.DATE, store, classNames, format);
				break;
			}
			if (checkDateTimeFormat(expression, classNames, format, r, monitor)) {
				column= new RDataTableColumn(columnIndex, columnName, expression, elementName,
						IRDataTableVariable.DATETIME, store, classNames, format);
				break;
			}
			{	final FunctionCall call= r.createFunctionCall("rj:::.getFormatInfo"); //$NON-NLS-1$
				call.add("x", expression); //$NON-NLS-1$
				rObject= call.evalData(monitor);
			}
			{	final RIntegerStore formatInfo= RDataUtil.checkRIntVector(rObject).getData();
				RDataUtil.checkLengthGreaterOrEqual(formatInfo, 3);
				format.setAutoWidth(Math.max(formatInfo.getInt(0), 3));
				format.initNumFormat(formatInfo.getInt(1), formatInfo.getInt(2) > 0 ?
						formatInfo.getInt(2) + 1 : 0);
				column= new RDataTableColumn(columnIndex, columnName, expression, elementName,
						IRDataTableVariable.NUM, store, classNames, format);
				break;
			}
		case RStore.INTEGER:
			if (checkDateFormat(expression, classNames, format, r, monitor)) {
				column= new RDataTableColumn(columnIndex, columnName, expression, elementName,
						IRDataTableVariable.DATE, store, classNames, format);
				break;
			}
			if (checkDateTimeFormat(expression, classNames, format, r, monitor)) {
				column= new RDataTableColumn(columnIndex, columnName, expression, elementName,
						IRDataTableVariable.DATETIME, store, classNames, format);
				break;
			}
			{	final FunctionCall call= r.createFunctionCall("rj:::.getFormatInfo"); //$NON-NLS-1$
				call.add("x", expression); //$NON-NLS-1$
				rObject= call.evalData(monitor);
			}
			{	final RIntegerStore formatInfo= RDataUtil.checkRIntVector(rObject).getData();
				RDataUtil.checkLengthGreaterOrEqual(formatInfo, 1);
				format.setAutoWidth(Math.max(formatInfo.getInt(0), 3));
				column= new RDataTableColumn(columnIndex, columnName, expression, elementName,
						IRDataTableVariable.INT, store, classNames, format);
				break;
			}
		case RStore.CHARACTER:
			{	final FunctionCall call= r.createFunctionCall("rj:::.getFormatInfo"); //$NON-NLS-1$
				call.add("x", expression); //$NON-NLS-1$
				rObject= call.evalData(monitor);
			}
			{	final RIntegerStore formatInfo= RDataUtil.checkRIntVector(rObject).getData();
				RDataUtil.checkLengthGreaterOrEqual(formatInfo, 1);
				format.setAutoWidth(Math.max(formatInfo.getInt(0), 3));
				column= new RDataTableColumn(columnIndex, columnName, expression, elementName,
						IRDataTableVariable.CHAR, store, classNames, format);
				break;
			}
		case RStore.COMPLEX:
			{	final FunctionCall call= r.createFunctionCall("rj:::.getFormatInfo"); //$NON-NLS-1$
				call.add("x", expression); //$NON-NLS-1$
				rObject= call.evalData(monitor);
			}
			{	final RIntegerStore formatInfo= RDataUtil.checkRIntVector(rObject).getData();
				RDataUtil.checkLengthGreaterOrEqual(formatInfo, 3);
				format.setAutoWidth(Math.max(formatInfo.getInt(0), 3));
				format.initNumFormat(formatInfo.getInt(1), formatInfo.getInt(2) > 0 ?
						formatInfo.getInt(2) + 1 : 0);
				column= new RDataTableColumn(columnIndex, columnName, expression, elementName,
						IRDataTableVariable.CPLX, store, classNames, format);
				break;
			}
		case RStore.RAW:
			format.setAutoWidth(2);
			column= new RDataTableColumn(columnIndex, columnName, expression, elementName,
					IRDataTableVariable.RAW, store, classNames, format);
			break;
		case RStore.FACTOR:
			{	final FunctionCall call= r.createFunctionCall("levels"); //$NON-NLS-1$
				call.add(expression);
				rObject= call.evalData(monitor);
			}
			{	format.setAutoWidth(3);
				final RCharacterStore levels= RDataUtil.checkRCharVector(rObject).getData();
				final int l= RDataUtil.checkIntLength(levels);
				for (int i= 0; i < l; i++) {
					if (!levels.isNA(i)) {
						final int length= levels.getChar(i).length();
						if (length > format.getAutoWidth()) {
							format.setAutoWidth(length);
						}
					}
				}
				format.initFactorLevels(levels);
				column= new RDataTableColumn(columnIndex, columnName, expression, elementName,
						IRDataTableVariable.FACTOR, RFactorDataStruct.addLevels((RFactorStore) store, levels),
						classNames, format);
				break;
			}
		default:
			throw new UnexpectedRDataException("store type: " + store.getStoreType());
		}
		return column;
	}
	
	protected boolean checkDateFormat(final String expression, final List<String> classNames,
			final RDataFormatter formatter,
			final IRToolService r, final IProgressMonitor monitor) throws CoreException, UnexpectedRDataException {
		if (classNames.contains("Date")) { //$NON-NLS-1$
			formatter.initDateFormat(RDataFormatter.MILLIS_PER_DAY);
			formatter.setAutoWidth(10);
			return true;
		}
		return false;
	}
	
	protected boolean checkDateTimeFormat(final String expression, final List<String> classNames,
			final RDataFormatter formatter,
			final IRToolService r, final IProgressMonitor monitor) throws CoreException, UnexpectedRDataException {
		RObject rObject;
		if (classNames.contains("POSIXct")) { //$NON-NLS-1$
			formatter.initDateTimeFormat(RDataFormatter.MILLIS_PER_SECOND);
			formatter.setAutoWidth(27);
			
			{	final FunctionCall call= r.createFunctionCall("base::attr"); //$NON-NLS-1$
				call.add(expression);
				call.addChar("tzone"); //$NON-NLS-1$
				rObject= call.evalData(monitor);
			}
			if (rObject.getRObjectType() != RObject.TYPE_NULL) {
				formatter.setDateTimeZone(TimeZone.getTimeZone(RDataUtil.checkSingleCharValue(rObject)));
			}
			return true;
		}
		return false;
	}
	
	protected RDataTableColumn createNamesColumn(final String expression, final long count,
			final IRToolService r, final IProgressMonitor monitor) throws CoreException, UnexpectedRDataException {
		final RObject names= r.evalData(expression, null, RObjectFactory.F_ONLY_STRUCT, 1, monitor);
		if (names != null && names.getRObjectType() == RObject.TYPE_VECTOR
				&& names.getLength() == count
				&& (names.getData().getStoreType() == RStore.CHARACTER
						|| names.getData().getStoreType() == RStore.INTEGER)) {
			return createColumn(names.getData(), expression, null, -1, null, r, monitor);
		}
		return createAutoNamesColumn(count);
	}
	
	private RDataTableColumn createAutoNamesColumn(final long count) {
		final RDataFormatter format= new RDataFormatter();
		format.setAutoWidth(Math.max(Long.toString(count).length(), 3));
		return new RDataTableColumn(-1, null, null, null,
				IRDataTableVariable.INT, RObjectFactoryImpl.INT_STRUCT_DUMMY,
				ImCollections.newList(RObject.CLASSNAME_INTEGER),
				format);
	}
	
	protected abstract void appendOrderCmd(StringBuilder cmd, SortColumn sortColumn);
	
	
	private void runClean(final IRToolService r, final IProgressMonitor monitor) throws CoreException {
		clear(Lock.ERROR_STATE);
		cleanSorting(r, monitor);
		cleanFiltering(r, monitor);
		this.findManager.clean(r, monitor);
		cleanTmp(this.rCacheId, r, monitor);
	}
	
	private void cleanSorting(final IRToolService r, final IProgressMonitor monitor) throws CoreException {
		cleanIdx(r, monitor);
		if (this.rCacheSort != null) {
			cleanTmp(this.rCacheSort, r, monitor);
			this.rCacheSort= null;
		}
	}
	
	private void cleanFiltering(final IRToolService r, final IProgressMonitor monitor) throws CoreException {
		cleanIdx(r, monitor);
		if (this.rCacheFilter != null) {
			cleanTmp(this.rCacheFilter, r, monitor);
			this.rCacheFilter= null;
		}
	}
	
	private void cleanIdx(final IRToolService r, final IProgressMonitor monitor) throws CoreException {
		this.updateIdx= true;
		if (this.rCacheIdx != null) {
			cleanTmp(this.rCacheIdx, r, monitor);
			this.rCacheIdx= null;
		}
		if (this.rCacheIdxR != null) {
			cleanTmp(this.rCacheIdxR, r, monitor);
			this.rCacheIdxR= null;
		}
	}
	
	
	protected final StringBuilder getRCmdStringBuilder() {
		this.rStringBuilder.setLength(0);
		return this.rStringBuilder;
	}
	
	
	public boolean getAllColumnsEqual() {
		return false;
	}
	
	public RDataTableContentDescription getDescription() {
		return this.description;
	}
	
	@Override
	public long getColumnCount() {
		return this.columnCount;
	}
	
	public long getFullRowCount() {
		return this.fullRowCount;
	}
	
	@Override
	public long getRowCount() {
		return this.rowCount;
	}
	
	@Override
	public Object getDataValue(final long columnIndex, final long rowIndex, final int flags,
			final IProgressMonitor monitor) {
		try {
			final LazyRStore.Fragment<T> fragment= this.fragmentsLock.getFragment(
					this.dataStore, rowIndex, columnIndex, flags, monitor );
			if (fragment != null) {
				return getDataValue(fragment, rowIndex, columnIndex);
			}
			else {
				return handleMissingData(flags);
			}
		}
		catch (final LoadDataException e) {
			return handleLoadDataException(e, flags);
		}
	}
	
	protected abstract Object getDataValue(LazyRStore.Fragment<T> fragment, long rowIdx, long columnIdx);
	
	@Override
	public void setDataValue(final long columnIndex, final long rowIndex, final Object newValue) {
		throw new UnsupportedOperationException();
	}
	
	protected abstract Object getColumnName(LazyRStore.Fragment<T> fragment, long columnIdx);
	
	public boolean hasRealColumns() {
		return true;
	}
	
	public boolean hasRealRows() {
		return true;
	}
	
	public IDataProvider getColumnDataProvider() {
		return this.columnDataProvider;
	}
	
	public IDataProvider getRowDataProvider() {
		return this.rowDataProvider;
	}
	
	public IDataProvider getColumnLabelProvider() {
		return this.columnLabelProvider;
	}
	
	public IDataProvider getRowLabelProvider() {
		return this.rowLabelProvider;
	}
	
	
	protected IDataProvider createColumnDataProvider() {
		return new ColumnDataProvider();
	}
	
	protected IDataProvider createRowDataProvider() {
		return new RowDataProvider();
	}
	
	protected IDataProvider createColumnLabelProvider() {
		return null;
	}
	
	protected IDataProvider createRowLabelProvider() {
		return null;
	}
	
	protected ISortModel createSortModel() {
		return new SortModel();
	}
	
	private Object handleMissingData(final int flags) {
		return ((flags & IDataProvider.FORCE_SYNC) != 0) ? ERROR : LOADING;
	}
	
	private Object handleLoadDataException(final LoadDataException e, final int flags) {
		if (!e.isRecoverable()) {
			return ERROR;
		}
		reset();
		return handleMissingData(flags);
	}
	
	
	public ISortModel getSortModel() {
		return this.sortModel;
	}
	
	public SortColumn getSortColumn() {
		return this.sortColumn;
	}
	
	private void setSortColumn(final SortColumn column) {
		this.fragmentsLock.lock();
		try {
			if (Objects.equals(this.sortColumn, column)) {
				return;
			}
			this.sortColumn= column;
			
			clear(-1, -1, -1, true, false, false);
			
			this.findManager.reset(false);
		}
		finally {
			this.fragmentsLock.unlock();
		}
	}
	
	public void setFilter(final String filter) {
		this.fragmentsLock.lock();
		try {
			if ((this.filter != null) ? this.filter.equals(filter) : null == filter) {
				return;
			}
			this.filter= filter;
			
			clear(-1, -1, -1, false, true, false);
			
			this.findManager.reset(true);
			
			this.fragmentsLock.scheduleUpdate(null, null, null, 0, null);
		}
		finally {
			this.fragmentsLock.unlock();
		}
	}
	
	public String getFilter() {
		return this.filter;
	}
	
	
	public void addFindListener(final IFindListener listener) {
		this.findManager.addFindListener(listener);
	}
	
	public void removeFindListener(final IFindListener listener) {
		this.findManager.removeFindListener(listener);
	}
	
	public void find(final FindTask task) {
		this.findManager.find(task);
	}
	
	
	public long[] toDataIdxs(final long columnIndex, final long rowIndex) {
		if (getFilter() != null || getSortColumn() != null) {
			if (this.rowDataProvider instanceof AbstractRDataProvider.RowDataProvider) {
				final long rowIdx= ((AbstractRDataProvider.RowDataProvider) this.rowDataProvider)
						.getRowIdx(rowIndex);
				return new long[] { columnIndex, rowIdx };
			}
			return new long[] { columnIndex, -2 };
		}
		return new long[] { columnIndex, rowIndex };
	}
	
	
	public void addDataChangedListener(final IDataProviderListener listener) {
		this.dataListeners.add(listener);
	}
	
	public void removeDataChangedListener(final IDataProviderListener listener) {
		this.dataListeners.remove(listener);
	}
	
	protected void notifyListener(final LazyRStore.Fragment<?> item) {
		try {
			for (final IDataProviderListener listener : this.dataListeners.toArray()) {
				listener.onRowsChanged(item.getRowBeginIdx(), item.getRowEndIdx());
			}
		}
		catch (final Exception e) {
			StatusManager.getManager().handle(new Status(IStatus.ERROR, RUI.PLUGIN_ID, -1,
					"An error occurred when notifying about row updates.", e));
		}
	}
	
	
	public void reset() {
		clear(Lock.PAUSE_STATE);
		synchronized (this.initRunnable) {
			if (this.initScheduled) {
				return;
			}
			this.initScheduled= true;
		}
		try {
			final IStatus status= this.input.getTool().getQueue().add(this.initRunnable);
			if (status.getSeverity() >= IStatus.ERROR) {
				throw new CoreException(status);
			}
		}
		catch (final CoreException e) {
		}
	}
	
	private void clear(final int newState) {
		clear(newState, -1, -1, true, true, true);
	}
	
	private void clear(final int newState, final long filteredRowCount, final long fullRowCount,
			final boolean updateSorting, final boolean updateFiltering,
			final boolean clearFind) {
		this.fragmentsLock.lock();
		try {
			this.dataStore.clear(filteredRowCount);
			if (this.rowDataProvider instanceof AbstractRDataProvider<?>.RowDataProvider) {
				((RowDataProvider) this.rowDataProvider).rowNamesStore.clear(filteredRowCount);
			}
			this.updateSorting |= updateSorting;
			this.updateFiltering |= updateFiltering;
			
			if (newState >= 0 && this.fragmentsLock.state < Lock.ERROR_STATE) {
				this.fragmentsLock.state= newState;
			}
			this.fragmentsLock.clear();
			
			if (filteredRowCount >= 0) {
				this.rowCount= filteredRowCount;
				this.fullRowCount= fullRowCount;
			}
			
			if (clearFind) {
				this.findManager.clear(newState);
			}
		}
		finally {
			this.fragmentsLock.unlock();
		}
	}
	
	public void dispose() {
		this.disposeScheduled= true;
		schedule(this.cleanRunnable);
	}
	
}
