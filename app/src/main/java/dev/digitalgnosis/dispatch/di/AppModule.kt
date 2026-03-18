package dev.digitalgnosis.dispatch.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.digitalgnosis.dispatch.data.DispatchDatabase
import dev.digitalgnosis.dispatch.data.MessageDao
import dev.digitalgnosis.dispatch.data.ThreadDao
import dev.digitalgnosis.dispatch.data.ChatBubbleDao
import dev.digitalgnosis.dispatch.data.ThreadMessageDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): DispatchDatabase =
        Room.databaseBuilder(context, DispatchDatabase::class.java, "dispatch.db")
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun provideMessageDao(database: DispatchDatabase): MessageDao =
        database.messageDao()

    @Provides
    fun provideThreadDao(database: DispatchDatabase): ThreadDao =
        database.threadDao()

    @Provides
    fun provideThreadMessageDao(database: DispatchDatabase): ThreadMessageDao =
        database.threadMessageDao()

    @Provides
    fun provideChatBubbleDao(database: DispatchDatabase): ChatBubbleDao =
        database.chatBubbleDao()
}
