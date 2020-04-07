/* 
 * This file is part of the Sejda source code
 * Copyright 2015 by Andrea Vacondio (andrea.vacondio@gmail.com).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as 
 * published by the Free Software Foundation, either version 3 of the 
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.sejda.impl.sambox.component;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;
import static java.util.Optional.ofNullable;
import static org.apache.commons.lang3.StringUtils.defaultString;
import static org.sejda.commons.util.RequireUtils.requireNotNullArg;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.sejda.sambox.cos.COSArray;
import org.sejda.sambox.cos.COSName;
import org.sejda.sambox.pdmodel.PDDocument;
import org.sejda.sambox.pdmodel.PDDocumentCatalog;
import org.sejda.sambox.pdmodel.PDPage;
import org.sejda.sambox.pdmodel.PageNotFoundException;
import org.sejda.sambox.pdmodel.interactive.action.PDAction;
import org.sejda.sambox.pdmodel.interactive.action.PDActionGoTo;
import org.sejda.sambox.pdmodel.interactive.documentnavigation.destination.PDDestination;
import org.sejda.sambox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination;
import org.sejda.sambox.pdmodel.interactive.documentnavigation.destination.PDPageDestination;
import org.sejda.sambox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination;
import org.sejda.sambox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.sejda.sambox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility methods related to outline handling in SAMBox
 * 
 * @author Andrea Vacondio
 *
 */
public final class OutlineUtils {

    private static final Logger LOG = LoggerFactory.getLogger(OutlineUtils.class);

    private OutlineUtils() {
        // utility
    }

    /**
     * @param document
     * @return a set containing the the outline levels having at least one page destination
     */
    public static Set<Integer> getOutlineLevelsWithPageDestination(PDDocument document) {
        return getFlatOutline(document).stream().map(i -> i.level).collect(Collectors.toSet());
    }

    /**
     * @param current
     *            the outline item
     * @param catalog
     *            the catalog to look for in case of {@link PDNamedDestination}
     * @return the {@link PDPageDestination} for the given {@link PDOutlineItem} or an empty {@link Optional} if the destination is not a page. In case the outline item has a named
     *         destination, it is resolved against the given names tree.
     */
    public static Optional<PDPageDestination> toPageDestination(PDOutlineItem current, PDDocumentCatalog catalog) {
        try {
            PDDestination dest = current.getDestination();
            if (dest == null) {
                PDAction outlineAction = current.getAction();
                if (outlineAction instanceof PDActionGoTo) {
                    dest = ((PDActionGoTo) outlineAction).getDestination();
                }
            }
            if (dest instanceof PDNamedDestination && catalog != null) {
                dest = catalog.findNamedDestinationPage((PDNamedDestination) dest);
            }
            if (dest instanceof PDPageDestination) {
                return Optional.of((PDPageDestination) dest);
            }
        } catch (IOException e) {
            LOG.warn("Unable to get outline item destination ", e);
        }
        return Optional.empty();
    }

    /**
     * Creates a clone of the given page destination pointing to the given new page. If an error occur it falls back to a {@link PDPageXYZDestination}.
     * 
     * @param dest
     * @param destPage
     *            the new pointed page
     * @return the cloned page destination
     */
    public static PDPageDestination clonePageDestination(PDPageDestination dest, PDPage destPage) {
        requireNotNullArg(dest, "Cannot clone a null destination");
        try {
            PDDestination clonedDestination = PDDestination.create(dest.getCOSObject().duplicate());
            if (clonedDestination instanceof PDPageDestination) {
                ((PDPageDestination) clonedDestination).setPage(destPage);
                return (PDPageDestination) clonedDestination;
            }
        } catch (IOException e) {
            LOG.warn("Unable to clone page destination", e);
        }
        // this should never happen
        PDPageXYZDestination ret = new PDPageXYZDestination();
        ret.setPage(destPage);
        return ret;
    }

    /**
     * Tries to resolve the page pointed by a page destination. It's usually just a {@link PDPageDestination#getPage()} call but some tools out there wrongly put the page number
     * instead of the page ref. With this method we try to handle that.
     * 
     * @see <a href="https://github.com/wkhtmltopdf/wkhtmltopdf/issues/3275">wkhtmltopdf issue #3275</a>
     * 
     * @param destination
     * @param document
     * @return The page pointed by the destination or null
     */
    public static PDPage resolvePageDestination(PDPageDestination destination, PDDocument document) {
        PDPage page = destination.getPage();
        if (isNull(page) && destination.getPageNumber() >= 0) {
            try {
                LOG.debug("Found page number in page destination, expected a page reference");
                return document.getPage(destination.getPageNumber());
            } catch (PageNotFoundException e) {
                LOG.warn(
                        "Unable to resolve page destination pointing to page {} (a page reference was expected, a number was found)",
                        destination.getPageNumber());
            }
        }
        return page;
    }

    /**
     * Copies the dictionary from the given {@link PDOutlineItem} to the destination one
     * 
     * @param from
     * @param to
     */
    public static void copyOutlineDictionary(PDOutlineItem from, PDOutlineItem to) {
        to.setTitle(defaultString(from.getTitle()));
        if (nonNull(from.getCOSObject().getDictionaryObject(COSName.C, COSArray.class))) {
            to.setTextColor(from.getTextColor());
        }
        to.setBold(from.isBold());
        to.setItalic(from.isItalic());
        if (from.isNodeOpen()) {
            to.openNode();
        } else {
            to.closeNode();
        }
    }

    /**
     * @param document
     * @return A sorted flat representation of the document outline
     */
    public static List<OutlineItem> getFlatOutline(PDDocument document) {
        return ofNullable(document.getDocumentCatalog().getDocumentOutline()).map(PDDocumentOutline::children)
                .map(c -> recurseFlatOutline(document, c, 1)).orElseGet(ArrayList::new).stream()
                .sorted(Comparator.comparingInt(i -> i.page)).filter(i -> i.page > 0).collect(Collectors.toList());
    }

    private static List<OutlineItem> recurseFlatOutline(PDDocument document, Iterable<PDOutlineItem> items, int level) {
        List<OutlineItem> result = new ArrayList<>();
        for (PDOutlineItem item : items) {
            toPageDestination(item, document.getDocumentCatalog()).ifPresent(d -> {
                int pageNumber = ofNullable(d.getPage())
                        .map(p -> document.getPages().indexOf(p) + 1 /* 0-based index */ )
                        .orElseGet(() -> d.getPageNumber() + 1);

                boolean specificLocationInPage = false;
                if (d instanceof PDPageXYZDestination) {
                    PDPageXYZDestination xyzPageDest = (PDPageXYZDestination) d;
                    // it's a specific page destination but not the top of the page
                    if (xyzPageDest.getPage() != null) {
                        specificLocationInPage = xyzPageDest
                                .getTop() != (int) xyzPageDest.getPage().getCropBox().getHeight();
                    }
                }

                result.add(new OutlineItem(item.getTitle(), pageNumber, level, specificLocationInPage));
            });
            result.addAll(recurseFlatOutline(document, item.children(), level + 1));
        }
        return result;
    }
}
