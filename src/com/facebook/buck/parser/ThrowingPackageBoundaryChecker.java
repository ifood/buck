/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.parser;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildFileTree;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.parser.config.ParserConfig;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.util.Optional;

/**
 * {@link PackageBoundaryChecker} implementation that throws an exception if any file in a set does
 * not belong to the same package as provided build target only if cell configuration allows that,
 * otherwise noop.
 */
public class ThrowingPackageBoundaryChecker implements PackageBoundaryChecker {

  private final LoadingCache<Cell, BuildFileTree> buildFileTrees;
  private static final Logger LOG = Logger.get(ThrowingPackageBoundaryChecker.class);

  public ThrowingPackageBoundaryChecker(LoadingCache<Cell, BuildFileTree> buildFileTrees) {
    this.buildFileTrees = buildFileTrees;
  }

  @Override
  public void enforceBuckPackageBoundaries(
      Cell targetCell, BuildTarget target, ImmutableSet<ForwardRelativePath> paths) {

    ForwardRelativePath basePath = target.getCellRelativeBasePath().getPath();

    ParserConfig.PackageBoundaryEnforcement enforcing =
        targetCell
            .getBuckConfigView(ParserConfig.class)
            .getPackageBoundaryEnforcementPolicy(
                basePath.toPath(targetCell.getFilesystem().getFileSystem()));
    if (enforcing == ParserConfig.PackageBoundaryEnforcement.DISABLED) {
      return;
    }

    BuildFileTree buildFileTree = buildFileTrees.getUnchecked(targetCell);
    boolean isBasePathEmpty = basePath.isEmpty();

    for (ForwardRelativePath path : paths) {
      Optional<RelPath> ancestor =
          buildFileTree.getBasePathOfAncestorTarget(
              path.toRelPath(targetCell.getFilesystem().getFileSystem()));
      // It should not be possible for us to ever get an Optional.empty() for this because that
      // would require one of two conditions:
      // 1) The source path references parent directories, which we check for above.
      // 2) You don't have a build file above this file, which is impossible if it is referenced in
      //    a build file *unless* you happen to be referencing something that is ignored.
      if (!ancestor.isPresent()) {
        throw new IllegalStateException(
            String.format(
                "Target '%s' refers to file '%s', which doesn't belong to any package. "
                    + "More info at:\nhttps://buck.build/about/overview.html\n",
                target, path));
      }
    }
  }

  private static void warnOrError(
      ParserConfig.PackageBoundaryEnforcement enforcing,
      String formatString,
      Object... formatArgs) {
    if (enforcing == ParserConfig.PackageBoundaryEnforcement.ENFORCE) {
      throw new HumanReadableException(formatString, formatArgs);
    } else {
      LOG.warn(formatString, formatArgs);
    }
  }
}
