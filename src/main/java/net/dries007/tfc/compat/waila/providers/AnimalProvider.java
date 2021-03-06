/*
 * Work under Copyright. Licensed under the EUPL.
 * See the project README.md and LICENSE.txt for more information.
 */

package net.dries007.tfc.compat.waila.providers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nonnull;

import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraftforge.common.IShearable;

import net.dries007.tfc.api.types.IAnimalTFC;
import net.dries007.tfc.compat.waila.interfaces.IWailaEntity;
import net.dries007.tfc.util.calendar.CalendarTFC;
import net.dries007.tfc.util.calendar.ICalendar;
import net.dries007.tfc.util.calendar.ICalendarFormatted;

public class AnimalProvider implements IWailaEntity
{
    @Nonnull
    @Override
    public List<String> getTooltip(@Nonnull Entity entity, @Nonnull NBTTagCompound nbt)
    {
        List<String> currentTooltip = new ArrayList<>();
        if (entity instanceof IAnimalTFC)
        {
            IAnimalTFC animal = (IAnimalTFC) entity;
            boolean familiarized = animal.getFamiliarity() > 0.15f;
            if (animal.getAdultFamiliarityCap() > 0)
            {
                currentTooltip.add(new TextComponentTranslation(familiarized ? "waila.tfc.animal.familiarized" : "waila.tfc.animal.not_familiarized").getFormattedText());
            }
            switch (animal.getAge())
            {
                case CHILD:
                    long endPlayerTick = (animal.getBirthDay() + animal.getDaysToAdulthood()) * ICalendar.TICKS_IN_DAY;
                    long delta = endPlayerTick - CalendarTFC.PLAYER_TIME.getTicks();
                    long endCalendarTick = CalendarTFC.CALENDAR_TIME.getTicks() + delta;
                    String date = ICalendarFormatted.getTimeAndDate(endCalendarTick, CalendarTFC.CALENDAR_TIME.getDaysInMonth());
                    currentTooltip.add(new TextComponentTranslation("waila.tfc.animal.childhood_end", date).getFormattedText());
                    break;
                case OLD:
                    currentTooltip.add(new TextComponentTranslation("waila.tfc.animal.old").getFormattedText());
                    break;
                case ADULT:
                    if (familiarized)
                    {
                        if (animal.isReadyToMate())
                        {
                            currentTooltip.add(new TextComponentTranslation("waila.tfc.animal.can_mate").getFormattedText());
                        }
                        if (animal.isFertilized())
                        {
                            if (animal.getType() == IAnimalTFC.Type.MAMMAL)
                            {
                                // Pregnancy end time not possible due to how it is handled currently
                                // in 1.15, refactor animals to hold an object (`AnimalProperties`) with all needed data, serializable and sync to client via NBT
                                currentTooltip.add(new TextComponentTranslation("waila.tfc.animal.pregnant").getFormattedText());
                            }
                            else
                            {
                                currentTooltip.add(new TextComponentTranslation("tfc.tooltip.fertilized").getFormattedText());
                            }
                        }
                        if (animal.isReadyForAnimalProduct())
                        {
                            if (animal instanceof IShearable)
                            {
                                currentTooltip.add(new TextComponentTranslation("waila.tfc.animal.can_shear").getFormattedText());
                            }
                            else if (animal.getType() == IAnimalTFC.Type.OVIPAROUS)
                            {
                                currentTooltip.add(new TextComponentTranslation("waila.tfc.animal.has_eggs").getFormattedText());
                            }
                            else
                            {
                                currentTooltip.add(new TextComponentTranslation("waila.tfc.animal.has_milk").getFormattedText());
                            }
                        }
                    }
                    break;
            }
        }
        return currentTooltip;
    }

    @Nonnull
    @Override
    public List<Class<?>> getLookupClass()
    {
        return Collections.singletonList(IAnimalTFC.class);
    }
}
