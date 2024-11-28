package com.example.myandroidapp.todo.data.remote

import com.example.myandroidapp.todo.data.Post
data class Payload(val item: Post)

data class PostEvent(val event:String,val payload: Payload)