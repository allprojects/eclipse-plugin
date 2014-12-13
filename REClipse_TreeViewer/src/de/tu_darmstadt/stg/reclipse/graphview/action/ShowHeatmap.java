package de.tu_darmstadt.stg.reclipse.graphview.action;

import de.tu_darmstadt.stg.reclipse.graphview.Activator;
import de.tu_darmstadt.stg.reclipse.graphview.Images;
import de.tu_darmstadt.stg.reclipse.graphview.Texts;
import de.tu_darmstadt.stg.reclipse.graphview.view.CustomGraph;

import org.eclipse.jface.action.Action;

public class ShowHeatmap extends Action {

  private final CustomGraph graph;

  private boolean status;

  public ShowHeatmap(final CustomGraph g) {
    graph = g;
    status = false;

    setText(Texts.Show_Heatmap);
    setToolTipText(Texts.Show_Heatmap_Tooltip);
    setImageDescriptor(Activator.getImageDescriptor(Images.ZOOM_IN));
  }

  @Override
  public void run() {
    status = !status;

    graph.setHeatmapEnabled(status);
    graph.updateGraph();
  }
}